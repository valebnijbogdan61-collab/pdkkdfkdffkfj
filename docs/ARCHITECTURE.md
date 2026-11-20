# Архітектура (чернетка)

MainActivity (ui) -> вибір стилю + гра -> OverlayService
OverlayService -> кнопка -> ScreenCapture -> OcrEngine -> ActionPlanner -> TapInjector

- OverlayService — foreground service, TYPE_APPLICATION_OVERLAY
- ScreenCapture — MediaProjection -> Bitmap
- OcrEngine — ML Kit Text Recognition (офлайн)
- TapInjector — AccessibilityService + dispatchGesture()
