# ClosedLens - 완전 오프라인 다국어 화면 번역

**ClosedLens**는 외부 네트워크 연결 없이 100% 로컬 디바이스(ONNX Runtime)에서 화면 캡처, OCR(문자 인식), 다국어 번역을 수행하는 개인정보 보호 중심의 Android 애플리케이션입니다.

---

## 🌟 주요 특징

1. **완전 오프라인 & Zero Telemetry**
   - Android Manifest에서 `INTERNET` 및 `ACCESS_NETWORK_STATE` 권한을 완전히 박탈(`tools:node="remove"`).
   - 진단 데이터 및 원격 텔레메트리 전송 일체 차단.

2. **화면 내 다국어 혼합 텍스트 자동 감지 → 지정한 도착어로 번역**
   - 영어, 일본어, 중국어, 스페인어, 프랑스어, 독일어, 러시아어 등 **한 화면에 여러 언어가 섞여 있어도 각 텍스트 블록별로 언어를 실시간 자동 감지**.
   - 사용자가 설정한 **Destination(도착) 언어**(기본: 한국어)로 각각 독립 변환.
   - 이미 도착어와 동일한 언어의 텍스트는 원문을 유지하여 왜곡 방지.

3. **온디맨드(On-demand) 화면 캡처**
   - 상시 화면 녹화가 아닌, **플로팅 버튼을 누른 순간에만** 1프레임을 즉시 캡처하고 VirtualDisplay를 해제하여 시스템 상단 "녹화 타이머" 표시가 남지 않습니다.

4. **Android 15+ 16 KB 메모리 페이지 크기 최적화**
   - Android 15(API 35) 및 Google Play 최신 요구사항인 16 KB Page Alignment 지원.
   - `packaging { jniLibs { useLegacyPackaging = false } }` 및 ONNX Runtime 1.20.0+ 적용.

5. **모델 다운로드 링크 원클릭 복사**
   - 앱 내에서 번역 가중치 모델 다운로드 링크를 원클릭으로 클립보드에 복사하여 브라우저에서 편리하게 다운로드 가능.

6. **결과 캐싱 없음 (신선한 추론)**
   - 이전 번역 결과를 재사용하여 발생하는 번역 품질 저하를 방지하기 위해 매 실행 시 독립적인 추론을 수행합니다.

---

## 📱 시스템 요구사항
- **Android OS**: Android 8.0 이상 (API 26+)
- **권장 사양**: RAM 6GB 이상 (8GB 이상 권장, 4GB 기기는 백그라운드 앱 정리 권장)
- **필요 저장 공간**: 약 700MB (번역 가중치 파일 포함)

---

## 📦 모델 준비 및 설치

### 1. 번역 모델 (NLLB-200-600M ONNX)
- 앱 메인 화면의 **[다운로드 링크 복사]** 버튼을 눌러 링크를 복사하거나 아래 저장소에서 가중치를 다운로드합니다:
  - Hugging Face: `https://huggingface.co/facebook/nllb-200-distilled-600M`
- Optimum CLI를 통한 ONNX 변환:
  ```bash
  pip install optimum[onnxruntime]
  optimum-cli export onnx --model facebook/nllb-200-distilled-600M --task translation ./nllb-onnx/
  ```
- 변환된 디렉터리의 필수 3개 파일을 하나의 ZIP 파일로 압축:
  - `encoder_model.onnx`
  - `decoder_model_merged.onnx`
  - `tokenizer.json`
- 스마트폰으로 ZIP 파일을 복사한 뒤, 앱 메인 화면에서 **[모델 ZIP 가져오기]**를 눌러 선택합니다.

### 2. OCR 모델 (PaddleOCR v5 ONNX)
- `app/src/main/assets/ocr/` 디렉터리에 배치:
  - `det.onnx` (텍스트 영역 검출)
  - `rec.onnx` (텍스트 인식)
  - `cls.onnx` (방향 분류)
  - `ppocr_keys.txt` (문자 어휘 사전)

---

## 🚀 사용 방법
1. 앱을 실행하고 **다른 앱 위에 표시(오버레이)** 권한을 허용합니다.
2. NLLB 번역 모델 ZIP을 임포트합니다.
3. 원하는 **번역 도착어**를 선택합니다 (기본값: 한국어).
4. **[캡처 플로팅 버튼 시작]**을 누르고 화면 캡처(MediaProjection) 권한을 허용합니다.
5. 번역하고 싶은 화면(웹툰, 소설, 게임, 해외 앱 등)에서 화면의 플로팅 버튼을 탭합니다.
6. 캡처된 화면에서 **[번역]**을 누르면 화면 내의 모든 언어가 자동 판별되어 지정한 언어로 오버레이됩니다.
