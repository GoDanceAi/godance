// Compare the complete output trees without stripping formatting, assets or hashes.
// Usage: node scripts/discovery/output-parity.mjs B0_DIST CANDIDATE_DIST
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';
const [before, after] = process.argv.slice(2);
if (!before || !after) throw new Error('Pass B0_DIST and CANDIDATE_DIST directories.');
function manifest(directory, prefix = '') {
  return Object.fromEntries(readdirSync(join(directory,prefix)).sort().flatMap(name => {
    const relative = prefix ? `${prefix}/${name}` : name;
    return statSync(join(directory,relative)).isDirectory()
      ? Object.entries(manifest(directory,relative))
      : [[relative,createHash('sha256').update(readFileSync(join(directory,relative))).digest('hex')]];
  }));
}
const base=manifest(resolve(before));const candidate=manifest(resolve(after));
const allowed=['docs/index.html','llms.txt','llms-full.txt'];
const added=Object.keys(candidate).filter(p=>!(p in base));
const removed=Object.keys(base).filter(p=>!(p in candidate));
const changed=Object.keys(base).filter(p=>p in candidate&&base[p]!==candidate[p]);
const unexpected=changed.filter(p=>!allowed.includes(p));
console.log(JSON.stringify({basePaths:Object.keys(base).length,candidatePaths:Object.keys(candidate).length,added,removed,changed,unexpected,allowed,base,candidate},null,2));
process.exitCode=added.length||removed.length||unexpected.length?1:0;
