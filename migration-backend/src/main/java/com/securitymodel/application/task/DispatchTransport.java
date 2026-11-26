package com.securitymodel.application.task;

public interface DispatchTransport {

    /** Publish the durable event; throws when the broker does not confirm or returns it. */
    void publish(DispatchEvent event, String payloadJson);

    /** Send the same event to a TTL retry queue; the header carries the retry count. */
    void publishRetry(DispatchEvent event, String payloadJson, int retryCount);

    /**
     * Send to a specific retry delay without incrementing the header. Used when the ledger
     * cannot be read/written (DB outage) and the normal retry accounting is unavailable.
     */
    void publishRetryAfter(DispatchEvent event, String payloadJson, int retryCount,
                           int delaySeconds);

    /** Send a poison/unrecoverable message to the durable DLQ. */
    void publishDead(DispatchEvent event, String payloadJson, String reason);
}
