# Changelog

## 1.0.0

- First version, for Kotlin Multiplatform: Android, JVM desktop, iOS (arm64 device + simulator), macOS arm64,
  JavaScript and WebAssembly, published to Maven Central as `io.github.rajumark:retake`.
- `Retake().check(argb, width, height)` / `checkRgb(...)` return a `PhotoCheck`: a 0..1 score for blur, dark, bright,
  glare, noise and finger, the issues over the line (default 0.5) and a short hint per issue.
- Android: `check(bitmap)` and `check(context, uri)` (reduced-size decode + EXIF rotation).
- Screenshots, documents and graphics count as good photos.
- Pure Kotlin inference with no dependencies, int8 weights (0.5 MB). Parity with the reference on 13 images is tested
  on every target.
- Hoverfly Community License: free up to 10,000 monthly active devices per product.
