# 쿠비스 (안드로이드 런처 + 음성 비서) v3

홈 화면을 대신하는 런처이자, Claude API로 말로 휴대폰 일을 시키는 비서. Kotlin.

## 화면
- 왼쪽: 달력 (휴대폰 캘린더 = 구글 캘린더 일정)
- 가운데(메인): 날씨(Open-Meteo, 키 불필요) · 시계 · 할 일 · 쿠비스 버튼
- 오른쪽: 메모
- 아래: 고정 앱 5개, 위로 쓸면 전체 앱(검색), 위에서 아래로 쓸면 알림창
- 측면 버튼 길게 누르기(디지털 어시스턴트) → 어느 화면에서든 쿠비스 대화 창

## 말로 할 수 있는 일
일정 추가·조회·수정·삭제, 할 일 추가·완료·수정·삭제, 메모 저장·검색·덧붙이기·고정·삭제,
날씨, 알람·타이머, 앱 열기·정리·삭제 창, 고정줄 바꾸기, 페이지 이동, 연락처·전화·문자,
손전등, 볼륨, 설정 화면, 지도, 배터리·저장공간, 웹 검색

## 빌드
- 필요: JDK 17, Android SDK (platform 35)
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- 고정 서명 키(`app/kuvis.keystore`)라서 새로 빌드해도 덮어 설치됨

## 파일 (app/src/main/java/com/t/kuvis)
- HomeActivity.kt — 런처(페이지·고정줄·전체 앱·설정 메뉴)
- Pages.kt — 달력 / 날씨·시계·할 일 / 메모 페이지
- AssistActivity.kt — 대화 창
- KuvisCore.kt — 음성 인식·출력, Claude 스트리밍과 도구 반복, 설정 창
- KuvisPanel.kt — 대화 창 화면
- PhoneTools.kt — 쿠비스가 쓰는 휴대폰 도구
- Store.kt — 할 일·메모·고정줄 저장
- Weather.kt — 날씨
- VoiceServices.kt — 디지털 어시스턴트 등록(측면 버튼)
