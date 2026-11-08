// Pure JS fixtures: no browser, HTTP, JDBC or external packages.
const assert = require('node:assert/strict');
const vm = require('node:vm');

function verify(source) {
  const script = source.match(/private static final String SNAPSHOT = """([\s\S]*?)""";/)[1];
  const canary = 'DO_NOT_EMIT_secret';
  function snapshot({status = 200, missing = false, error = false, code = canary, hidden = false, noTiming = false, unsupportedStatus = false} = {}) {
    const element = {getBoundingClientRect: () => ({width: 10, height: 10})};
    const context = {
      document: {
        documentURI: error ? 'chrome-error://chromewebdata/' : 'https://task-manager-test-588r.onrender.com/login?token=' + canary,
        readyState: 'complete',
        getElementsByTagName: () => ({length: 20}),
        querySelector: () => ({textContent: code}),
        querySelectorAll: () => missing ? [] : [element]
      },
      performance: {getEntriesByType: () => noTiming ? [] : [{responseStatus: unsupportedStatus ? undefined : status, redirectCount: 2,
        responseStart: 30, requestStart: 10, duration: 50, type: 'navigate'}]},
      getComputedStyle: () => ({visibility: hidden ? 'hidden' : 'visible', display: 'block'})
    };
    return vm.runInNewContext('(function(){' + script + '\n})(["#username","#password"],["ERR_NAME_NOT_RESOLVED"])', context);
  }
  assert.equal(snapshot().http, 200);
  assert.equal(snapshot().markers, true);
  assert.equal(snapshot().redirects, 2);
  assert.equal(snapshot().responseMs, 20);
  assert.equal(snapshot({missing:true}).markers, false);
  assert.equal(snapshot({hidden:true}).markers, false);
  for (const status of [429,502,503,0]) assert.equal(snapshot({status}).http, status);
  assert.equal(snapshot({noTiming:true}).http, -1);
  assert.equal(snapshot({unsupportedStatus:true}).http, -1);
  assert.equal(snapshot({error:true,code:'ERR_NAME_NOT_RESOLVED'}).error, 'ERR_NAME_NOT_RESOLVED');
  assert.equal(snapshot({error:false,code:'ERR_NAME_NOT_RESOLVED'}).error, 'UNKNOWN');
  assert.equal(snapshot({error:true}).error, 'UNKNOWN');
  assert(!JSON.stringify(snapshot({error:true})).includes(canary));
  return 'PASS: passive Navigation Timing/DOM script fixtures; no browser or network';
}
module.exports = verify;
if (require.main === module) {
  const fs = require('node:fs');
  const path = require('node:path');
  console.log(verify(fs.readFileSync(path.join(__dirname, '../../ui-tests/src/test/java/ru/tarkovskiy/ui/reporting/NavigationDiagnostics.java'), 'utf8')));
}
