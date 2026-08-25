import fs from 'node:fs';

const file = process.argv[2];
if (!file) throw new Error('usage: patch-crc32-android.mjs <@node-rs/crc32/index.js>');
const source = fs.readFileSync(file, 'utf8');
const before = `  case 'android':
    if (arch !== 'arm64') {
      throw new Error(\`Unsupported architecture on Android \${arch}\`)
    }
    localFileExisted = existsSync(join(__dirname, 'crc32.android-arm64.node'))
    try {
      if (localFileExisted) {
        nativeBinding = require('./crc32.android-arm64.node')
      } else {
        nativeBinding = require('@node-rs/crc32-android-arm64')
      }
    } catch (e) {
      loadError = e
    }
    break`;
const after = `  case 'android':
    if (arch === 'arm64') {
      localFileExisted = existsSync(join(__dirname, 'crc32.android-arm64.node'))
      try {
        if (localFileExisted) {
          nativeBinding = require('./crc32.android-arm64.node')
        } else {
          nativeBinding = require('@node-rs/crc32-android-arm64')
        }
      } catch (e) {
        loadError = e
      }
    } else if (arch === 'arm') {
      localFileExisted = existsSync(join(__dirname, 'crc32.android-arm-eabi.node'))
      try {
        if (localFileExisted) {
          nativeBinding = require('./crc32.android-arm-eabi.node')
        } else {
          nativeBinding = require('@node-rs/crc32-android-arm-eabi')
        }
      } catch (e) {
        loadError = e
      }
    } else {
      throw new Error(\`Unsupported architecture on Android \${arch}\`)
    }
    break`;
if (!source.includes(before)) throw new Error('unexpected @node-rs/crc32 Android loader');
fs.writeFileSync(file, source.replace(before, after));
