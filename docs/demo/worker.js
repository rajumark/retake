// Runs the Retake Kotlin/Wasm build off the main thread, so the page never waits on the model.
// Messages in: {id, input, option}. Messages out: {type: "ready"|"error"|"result", ...}.
let model;

try {
  model = await import("./wasm/retake-demo.mjs");
  model.load();
  postMessage({ type: "ready" });
} catch (e) {
  model = null;
  postMessage({ type: "error", message: String(e && e.message || e) });
}

onmessage = (event) => {
  if (!model) return;
  const { id, input, option } = event.data;
  const t = performance.now();
  const data = JSON.parse(model.run(input, option || ""));
  postMessage({ type: "result", id, data, ms: performance.now() - t });
};
