// Browsers slow down timers in background tabs, but not in a worker. Manual control depends on a
// steady beat, so the beat lives here.
setInterval(() => postMessage(0), 50);
