import JSEncrypt from 'jsencrypt'

const publicKey = 'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCVfHM8ETn96h/AkBsAv9hMSPoJRaOcZ13XkRzJxKXzL13Hw3CxXKMx66CdCqIP6fiGAWe+v97+L56RCGuWBJk/YkGTjywk+LUaszujH1GfcGAvcLUWiwPrA8+J4RF0Fosyh072p9zmD6CTquer/+s3gV/+XczBiYRPoq4/1RCsWQIDAQAB'

export function encrypt(value) {
  const encryptor = new JSEncrypt()
  encryptor.setPublicKey(publicKey)
  return encryptor.encrypt(value)
}
