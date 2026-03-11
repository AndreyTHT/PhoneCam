# Bluetooth — Android App

Нативное Android приложение. Телефон-камера становится сервером — второй телефон
открывает браузер и смотрит стрим.

## Требования

- Android Studio (Hedgehog или новее)
- JDK 11+
- Android 5.1+ (API 22) на устройстве

## Сборка

1. Открой Android Studio
2. File → Open → выбери папку `BluetoothApp`
3. Дождись синхронизации Gradle
4. Build → Build Bundle(s)/APK(s) → Build APK(s)
5. APK будет в `app/build/outputs/apk/debug/`

Или через кабель: Run → Run 'app' (установится сразу на телефон)

## Использование

1. Запусти приложение на телефоне-камере
2. Нажми **START**
3. Приложение покажет URL, например: `http://192.168.1.42:8080`
4. На телефоне-зрителе открой этот URL в браузере
5. Смотри стрим

## Фоновая работа

Приложение работает как Foreground Service — остаётся активным даже когда
экран выключен или ты переключаешься на другие приложения.
В шторке уведомлений есть кнопка **Stop** для остановки.

## Архитектура

- `StreamServer.java` — NanoHTTPD WebSocket сервер (порт 8080)
  - HTTP GET / → отдаёт viewer.html в браузер
  - WebSocket → получает подключения зрителей, рассылает JPEG фреймы
- `CameraStreamService.java` — Foreground Service
  - Camera2 API → ImageReader (JPEG) → broadcastFrame()
- `MainActivity.java` — UI: IP, статус, viewers, FPS, кнопка Start/Stop
