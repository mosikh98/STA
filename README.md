# Screen Translate

Android app: floating button -> screenshot -> your own OpenAI-compatible vision API ->
translated text drawn over the original text (like Google Translate's screen overlay).

## Build on GitHub
1. Push this folder to `main` of a GitHub repo.
2. Actions -> "Build APK" (runs automatically or manually).
3. Download artifact `ScreenTranslate-debug` -> install the APK.

## Use
1. Pick the provider. Gemini: just paste the API key. Custom: enter Base URL + key. Press "تست اتصال".
   Use "دریافت لیست مدل‌ها" to list the models available for your key and pick one.
2. Pick the box-format that matches your model (Gemini: first option, Qwen-VL: third option).
   If the model can't give coordinates, choose "text panel only".
3. "شروع ترجمه روی صفحه" -> grant overlay + screen-capture permissions.
4. Tap the floating button -> menu: translate page / clear translation / stop.

## Speed / look settings
- Reasoning effort (lower = faster), screenshot width, opaque labels.
- Translation is streamed: labels appear one by one while the model is still answering.

## Methods
- Fast (default): ML Kit OCR on the phone finds text + boxes, only the text goes to your API.
- Vision model: whole screenshot goes to the model (slower, compact line output, streamed).
- Offline: ML Kit OCR + ML Kit on-device translation (no API key; language models download once).
