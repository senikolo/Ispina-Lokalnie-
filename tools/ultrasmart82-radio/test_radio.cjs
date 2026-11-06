const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const ui = fs.readFileSync(process.argv[2], 'utf8');
const start = ui.indexOf('(function(){', ui.indexOf('NATIVE RADIO BACKGROUND + MEDIA NOTIFICATION'));
const end = ui.indexOf(';/* Ispina Lokalnie 1.82.98', start);
assert.ok(start > 0 && end > start);
const card = ui.split('\n').find(line => line.includes('data-station="krakow"'));
for (const id of ['rtvKrakow', 'rtvKrakowRetry', 'rtvStatusKrakow']) {
    assert.ok(card.includes('id="' + id + '"'));
}

const clicks = new Map();
const elements = new Map();
const storage = new Map();
const commands = [];
const context = {
    document: {
        getElementById(id) {
            if (id === 'ilBackgroundRadio') return null;
            if (!elements.has(id)) {
                elements.set(id, {
                    dataset: {},
                    classList: { add() {}, remove() {}, toggle() {} },
                    addEventListener(type, callback) {
                        assert.equal(type, 'click');
                        clicks.set(id, callback);
                    },
                });
            }
            return elements.get(id);
        },
        addEventListener() {},
    },
    window: {
        IL_BUILD: { versionName: '2.0.0-smart.82', versionCode: 20356 },
        IL_OPEN_EXTERNAL_1862(uri) { commands.push(new URL(uri)); return true; },
    },
    navigator: {},
    localStorage: { getItem: storage.get.bind(storage), setItem: storage.set.bind(storage) },
    setTimeout() {},
};
vm.runInNewContext(ui.slice(start, end), context);
function click(id) {
    clicks.get(id)({ preventDefault() {}, stopImmediatePropagation() {} });
}
click('rtvKrakow');
assert.equal(commands.at(-1).hostname, 'play');
assert.equal(commands.at(-1).searchParams.get('url'), 'https://stream3.nadaje.com:9117/radiokrakow');
assert.equal(commands.at(-1).searchParams.get('name'), 'Radio Kraków');
assert.equal(elements.get('rtvNowName').textContent, 'Radio Kraków');
click('rtvNowToggle');
assert.equal(commands.at(-1).hostname, 'toggle');
click('rtvStop');
assert.equal(commands.at(-1).hostname, 'stop');
click('rtvEska');
assert.equal(commands.at(-1).searchParams.get('name'), 'Radio ESKA Bochnia');
console.log('PASS: Radio Kraków card, play command, pause, stop and station switching');
