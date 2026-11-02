# Pelis LD TV

## Cómo dejarlo funcionando (una sola vez)
1. Sube **todo** el contenido de este ZIP a tu repositorio de GitHub (incluida la carpeta oculta `.github`).
2. El repositorio debe ser **público** (para que la app pueda leer las listas).
3. En el repositorio: Settings → Actions → General → Workflow permissions → **Read and write permissions** → Save.
4. Entra a la pestaña **Actions**. Al subir los archivos arrancan solos dos procesos:
   - **Build and Release APK**: compila la app. El APK queda en Releases (`apk-latest`).
   - **Actualizar listas M3U**: verifica las listas y las guarda en la carpeta `listas/`.
5. Instala el APK en tu dispositivo. Desde ahí las listas se actualizan solas cada día.

## Cómo se actualizan las listas
- Todos los días a las 06:00 UTC (03:00 en Uruguay), GitHub revisa cada lista de `config.json`.
- Modo `tolerante`: quita solo los canales claramente caídos y conserva los que podrían estar bloqueados por país.
- Resultado y conteo por lista: `listas/resumen.md`.
- Para agregar o quitar listas, edita `config.json` (campos `nombre`, `url`, `solo_peliculas`, `modo`).
