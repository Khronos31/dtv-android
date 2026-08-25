import fs from 'node:fs';
import path from 'node:path';

const [root, output] = process.argv.slice(2);
if (!root || !output) {
    throw new Error('usage: generate-npm-notice.mjs <node_modules> <output>');
}

const packages = new Map();
function visit(directory) {
    const packageFile = path.join(directory, 'package.json');
    if (fs.existsSync(packageFile)) {
        const pkg = JSON.parse(fs.readFileSync(packageFile, 'utf8'));
        if (pkg.name && pkg.version) {
            const license = typeof pkg.license === 'string'
                ? pkg.license
                : pkg.license ? JSON.stringify(pkg.license) : 'UNKNOWN';
            packages.set(`${pkg.name}@${pkg.version}`, license);
        }
    }
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        if (!entry.isDirectory() || entry.name === '.bin') continue;
        if (entry.name.startsWith('@')) visit(path.join(directory, entry.name));
        else if (directory === root || directory.includes(`${path.sep}node_modules${path.sep}`)) {
            visit(path.join(directory, entry.name));
        }
    }
}

visit(root);
const lines = [
    'EPGStation Android port — production npm dependency license list',
    'Generated from the bundled node_modules package.json files.',
    '',
    ...[...packages.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([name, license]) => `${name} — ${license}`),
    '',
];
fs.writeFileSync(output, `${lines.join('\n')}\n`);
