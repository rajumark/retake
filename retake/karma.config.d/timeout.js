// The models run tens of ms per call in a browser, so the parity and latency tests need more than
// mocha's 2 s default and Karma's 30 s no-activity limit.
config.set({
    client: { mocha: { timeout: 600000 } },
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 600000,
    captureTimeout: 600000,
});
