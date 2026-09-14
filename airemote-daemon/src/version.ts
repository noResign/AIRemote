import { createRequire } from 'node:module';

// package.json 是 daemon 版本的唯一来源；这里在运行时读取，保证
// `airemote --version`、/api/health 与 npm 包版本永远一致。
const require = createRequire(import.meta.url);
const pkg = require('../package.json') as { version: string };

export const VERSION = pkg.version;
