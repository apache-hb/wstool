const fs = require('node:fs');
const isNumber = require('is-number');

fs.mkdirSync('dist', { recursive: true });
fs.writeFileSync('dist/result.txt', String(isNumber(42)));
