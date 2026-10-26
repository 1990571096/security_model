import JSEncrypt from 'jsencrypt/bin/jsencrypt.min'

// 密钥对生成 http://web.chacuo.net/netrsakeypair

const publicKey = 'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCVfHM8ETn96h/AkBsAv9hMSPoJRaOcZ13XkRzJxKXzL13Hw3CxXKMx66CdCqIP6fiGAWe+v97+L56RCGuWBJk/YkGTjywk+LUaszujH1GfcGAvcLUWiwPrA8+J4RF0Fosyh072p9zmD6CTquer/+s3gV/+XczBiYRPoq4/1RCsWQIDAQAB'

// 加密
export function encrypt(txt) {
  const encryptor = new JSEncrypt()
  encryptor.setPublicKey(publicKey) // 设置公钥
  return encryptor.encrypt(txt) // 对需要加密的数据进行加密
}
