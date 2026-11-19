Bundled model (v1.3):
- blaze_face_short_range.tflite — official MediaPipe BlazeFace short-range
  float16 face detector.
  Source: https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite
  SHA-256: b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f
  Input [1,128,128,3] float; outputs regressors [1,896,16], classifiers [1,896,1].

No gender model is bundled. Drop a gender_attr_classifier_int8.tflite in this
folder to enable gender-targeted blurring; without it HaramBlur blurs ALL
detected faces regardless of the Who-to-Blur setting (all-faces fallback).
