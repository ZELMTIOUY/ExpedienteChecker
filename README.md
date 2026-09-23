# ExpedienteChecker 🤖

Bot en Java que consulta automáticamente el estado de tu expediente de extranjería en [Infoext2](https://infoext2.delegaciondelgobierno.gob.es/infoext2/) y te avisa por **Telegram** cuando cambia.

## ⚙️ Tecnología
- Java 17 + Microsoft Playwright (Chromium headless)
- Maven (build con fat-JAR)
- GitHub Actions (ejecución automática cada 30 min, gratis)

## 🚀 Cómo funciona

1. Abre Infoext2 en un navegador invisible (headless)
2. Rellena el formulario con tu NIE, fecha de solicitud y año de nacimiento
3. Lee el resultado y:
   - Si detecta **FAVORABLE** → 🎉 Telegram
   - Si detecta **DENEGADO/ARCHIVADO** → ⚠️ Telegram
   - Si está **en trámite** → silencio (sin spam)
4. Guarda capturas de pantalla para depuración

## 🔐 Configurar GitHub Secrets

Ve a **Settings → Secrets → Actions** en tu repositorio y añade:

| Secret            | Valor                        |
|-------------------|------------------------------|
| `MY_NIE`          | Tu NIE (ej. `Z1017349H`)     |
| `FECHA_SOLICITUD` | Fecha DD/MM/YYYY             |
| `ANIO_NACIMIENTO` | Año YYYY (ej. `1996`)        |
| `TELEGRAM_TOKEN`  | Token de tu bot de Telegram  |
| `CHAT_ID`         | Tu Chat ID de Telegram       |

## 🏃 Ejecutar localmente

```bash
# Compilar
mvn package

# Instalar Playwright Chromium
mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install chromium"

# Ejecutar
MY_NIE=Z1017349H FECHA_SOLICITUD=30/07/2026 ANIO_NACIMIENTO=1996 \
TELEGRAM_TOKEN=tu_token CHAT_ID=tu_chat_id \
java -jar target/expediente-checker.jar
```

## 📅 Ejecución automática

El workflow `.github/workflows/check_expediente.yml` se dispara:
- **Automáticamente** cada 30 minutos (GitHub Actions gratuito)
- **Manualmente** desde la pestaña Actions en GitHub

---

> ⚠️ **Aviso legal**: Este bot es para uso personal. Respeta los términos de uso del portal oficial.
