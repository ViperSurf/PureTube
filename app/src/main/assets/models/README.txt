Bundled models:

1) blaze_face_short_range.tflite — official MediaPipe BlazeFace short-range
   float16 face detector (v1.3).
   Source: https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite
   SHA-256: b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f
   Input [1,128,128,3] float; outputs regressors [1,896,16], classifiers [1,896,1].

2) model_gender_q.tflite — gender classifier (v1.5), so Who-to-Blur
   (FEMALE_ONLY by default) is a real classification, not a fallback.
   Source: https://github.com/shubham0204/Age-Gender_Estimation_TF-Android
   (file app/src/main/assets/model_gender_q.tflite, MIT License; the model
   was trained by that project's author on the UTKFace dataset).
   SHA-256: 7ab000d530a005ccd605a2826485b23d08690d0e4fb23bc4589a5b03861750c0
   Verified host-side with LiteRT before bundling: valid TFLite (TFL3),
   input [1,128,128,3] float32 normalized to [0,1], output [1,2] float32
   probabilities in the order [male, female] (per the source project's own
   code), summing to 1.0; on this app's bundled test portrait (a man) a
   tight face crop classifies Male at ~0.88-0.90.
   Note: the model expects a TIGHT face crop (UTKFace-style). The app
   classifies the detected face box with only a small margin; loose,
   background-heavy crops can mislead it.

If the gender model is ever removed, HaramBlur falls back to blurring ALL
detected faces regardless of the Who-to-Blur setting (all-faces fallback),
and Settings says so.
