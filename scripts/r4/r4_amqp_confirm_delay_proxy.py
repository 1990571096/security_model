"""AMQP 0-9-1 confirm-delay proxy for R4 fault injection.

Listens on a local port, forwards frames to the real RabbitMQ broker, and delays
Basic.Ack / Basic.Nack frames from broker to client. Used to reproduce a lost or
late publisher confirm without changing application code or hand-editing DB rows.
"""
import argparse
import asyncio
import datetime
import struct


def log(message):
    stamp = datetime.datetime.now().isoformat(timespec="milliseconds")
    print(f"{stamp} {message}", flush=True)


async def pump_client_to_broker(reader, writer):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except (ConnectionResetError, asyncio.IncompleteReadError):
        pass
    finally:
        writer.close()


async def pump_broker_to_client(reader, writer, delay_seconds):
    try:
        while True:
            header = await reader.readexactly(7)
            frame_type, channel, size = struct.unpack(">BHI", header)
            payload = await reader.readexactly(size)
            frame_end = await reader.readexactly(1)
            delayed = False
            if frame_type == 1 and size >= 4:
                class_id, method_id = struct.unpack(">HH", payload[:4])
                if class_id == 60 and method_id in (80, 120):
                    delayed = True
                    log(f"confirm frame delayed channel={channel} "
                        f"method={method_id} delay={delay_seconds}s")
                    await asyncio.sleep(delay_seconds)
                    log(f"confirm frame released channel={channel} method={method_id}")
            writer.write(header + payload + frame_end)
            await writer.drain()
            if delayed:
                log(f"confirm frame written channel={channel} method={method_id}")
    except (ConnectionResetError, asyncio.IncompleteReadError):
        pass
    finally:
        writer.close()


async def handle(client_reader, client_writer, broker_host, broker_port, delay_seconds):
    broker_reader, broker_writer = await asyncio.open_connection(broker_host, broker_port)
    log("connection opened")
    await asyncio.gather(
        pump_client_to_broker(client_reader, broker_writer),
        pump_broker_to_client(broker_reader, client_writer, delay_seconds),
    )
    log("connection closed")


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-port", type=int, default=5674)
    parser.add_argument("--broker-host", default="127.0.0.1")
    parser.add_argument("--broker-port", type=int, default=5673)
    parser.add_argument("--delay", type=float, default=7.0)
    args = parser.parse_args()
    server = await asyncio.start_server(
        lambda r, w: handle(r, w, args.broker_host, args.broker_port, args.delay),
        "127.0.0.1", args.listen_port)
    log(f"listening {args.listen_port} -> {args.broker_host}:{args.broker_port} "
        f"delay={args.delay}s")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
