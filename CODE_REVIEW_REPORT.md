# PocketTranspose 코드 리뷰 보고서

## 리뷰 범위

- Android Kotlin WebView PoC 전체 구조
- JavaScript 주입 및 Web Audio API 연결 검증 흐름
- Native UI에서 `evaluateJavascript()`로 제어하는 경로
- YouTube SPA 탐색 대응 코드
- README 및 third-party license 문서

## 종합 의견

현재 구현은 요구사항의 1차 PoC 범위인 “WebView 안에서 페이지를 열고, JS를 주입해 `<video>/<audio>`를 찾아 `createMediaElementSource()` 연결 성공/실패를 관찰한다”는 목적에는 맞게 구성되어 있다. 특히 초기 버전에서 `addJavascriptInterface()`를 사용하지 않고 Native → JS 호출을 `evaluateJavascript()`로 제한한 점, JS console log를 `PocketTransposeJS` 태그로 Logcat에 연결한 점, third-party DSP 라이브러리를 추가하지 않은 점은 요구사항과 잘 맞는다.

다만 이전 커밋 기준으로 document-start 주입 시점에 `document.documentElement`가 아직 준비되지 않은 경우 `MutationObserver.observe()`가 실패할 수 있는 리스크가 있었다. 이 경우 `window.__PocketTransposeInstalled`는 이미 `true`로 설정되었지만 observer/interval 설치가 중단되어 fallback reinjection으로도 복구되지 않을 수 있었다. 이번 수정에서는 observer 설치를 `document.documentElement || document.body`가 준비될 때까지 짧게 재시도하도록 변경했다.

## 주요 발견 사항 및 조치

| 구분 | 심각도 | 발견 사항 | 조치 상태 |
| --- | --- | --- | --- |
| JS 주입 안정성 | High | document-start 실행 시 `document.documentElement`가 없으면 observer 설치가 예외로 중단될 수 있음 | 수정 완료: root 준비 전 `installObserver()` 재시도 |
| Fallback 복구성 | High | 설치 플래그가 너무 일찍 설정되면 부분 설치 실패 상태에서 재주입이 무시될 수 있음 | 완화 완료: API/observer 설치 준비 후 설치 완료 상태로 진행 |
| Media 교체 대응 | Medium | YouTube SPA에서 media element가 바뀌어도 기존 graph 정리가 명확하지 않음 | 개선 완료: 다른 media 감지 시 기존 source/gain disconnect 시도 |
| 오류 기록 | Medium | 예외 객체 형식이 브라우저별로 다를 수 있음 | 개선 완료: `safeError()`로 name/message를 안정적으로 저장 |
| Pitch shift | Low | 실제 DSP가 아닌 placeholder임 | 의도된 상태: 라이선스 정책상 후순위 유지 |
| 자동 빌드 검증 | Medium | 현재 컨테이너가 Google Maven 접근을 차단해 AGP resolve 불가 | 미해결: 외부 환경 제한, README 수동 테스트 유지 |

## 현재 구현이 충족하는 요구사항

- 앱 실행 시 기본 URL `https://m.youtube.com` 로드
- JavaScript 및 DOM storage 활성화
- debug build에서만 WebView debugging 활성화
- AndroidX WebKit 기반 document-start 주입 시도
- `onPageFinished()` fallback 주입
- JS console log를 `PocketTransposeJS` Logcat tag로 출력
- `<video>/<audio>` 탐색
- `AudioContext.createMediaElementSource()` 성공/실패 로그 기록
- `GainNode` 연결
- Native slider에서 `setGain()` / `setPlaybackRate()` 호출
- Status 버튼에서 JSON 상태 로그 출력
- History API, `popstate`, `yt-navigate-finish` 기반 YouTube SPA 재탐색 시도
- Third-party DSP/npm/native binary 미사용

## 남은 리스크

1. **YouTube CORS/MSE/보안 정책 리스크**
   - `createMediaElementSource(video)` 호출 자체는 성공하더라도 실제 출력이 silence 처리될 가능성이 있다.
   - 이 문제는 코드 정적 리뷰만으로 판정할 수 없고 실제 Android WebView + YouTube 재생 테스트가 필요하다.

2. **AudioContext 사용자 제스처 정책**
   - WebView/Chromium 버전 및 사이트 정책에 따라 `AudioContext.resume()`이 사용자 제스처 없이 지연될 수 있다.
   - 현재는 slider/button 조작과 media playback 흐름에서 재시도가 가능하다.

3. **Fullscreen UX 완성도**
   - 기본 custom view attach/remove는 구현했지만 orientation, system bar restore, back-key fullscreen exit 등은 PoC 수준이다.

4. **빌드 재현성**
   - Gradle wrapper가 없다.
   - 현재 환경에서는 Google Maven 접근이 HTTP 403으로 차단되어 Android Gradle Plugin을 내려받을 수 없다.

## 권장 다음 단계

1. Android SDK와 Google Maven 접근이 가능한 환경에서 `gradle :app:assembleDebug`를 재실행한다.
2. 단순 HTML5 video 페이지에서 먼저 `media element source connected`와 gain 변경을 검증한다.
3. YouTube 모바일 페이지에서 `createMediaElementSource failed` 또는 silence 여부를 Logcat과 실제 청감으로 기록한다.
4. WebView fullscreen back handling과 system UI restore를 개선한다.
5. Pitch shift는 permissive license 후보 조사를 마친 뒤 별도 PR에서 진행한다.
