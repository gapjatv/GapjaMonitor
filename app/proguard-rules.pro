# OpenCV 는 네이티브에서 자바 클래스를 리플렉션으로 찾는다 — 이름을 지우면 안 된다.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
