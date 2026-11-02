#!/usr/bin/env python3
"""Descarga listas M3U, verifica cada canal y publica solo los que funcionan."""
import json, re, socket, ssl, sys, unicodedata, urllib.error, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

UA = "VLC/3.0.20 LibVLC/3.0.20"
TIMEOUT, WORKERS = 8, 40
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
MOVIE_RE = re.compile(r"\b(cine\w*|telecine|pelicula\w*|movie\w*|film\w*|cinema\w*|kino|western|run:?time)\b")
# "clásico/classic" solo cuenta si el canal no es de dibujos, música o series
CLASSIC_RE = re.compile(r"\b(clasic\w*|classic\w*)\b")
NOT_MOVIE_RE = re.compile(r"\b(toons?|cartoons?|anime|rock|music\w*|musica|hits|kids|infantil\w*|series|serie|novela\w*|sitcom\w*|tv shows?)\b")
GROUP_RE = re.compile(r'group-title="([^"]*)"', re.I)


def get(url, timeout=TIMEOUT):
    return urllib.request.urlopen(
        urllib.request.Request(url, headers={"User-Agent": UA}), timeout=timeout, context=CTX)


def parse(text):
    header, pending, name, out = "#EXTM3U", None, "", []
    for raw in text.splitlines():
        l = raw.strip()
        if not l:
            continue
        if l.upper().startswith("#EXTM3U"):
            header = l
        elif l.upper().startswith("#EXTINF"):
            pending, name = [l], (l.rsplit(",", 1)[-1].strip() or "Sin nombre")
        elif l.startswith("#"):
            if pending is not None:
                pending.append(l)
        elif pending is not None:
            pending.append(l)
            out.append({"name": name, "url": l, "lines": pending})
            pending = None
    return header, out


def is_movie(c):
    m = GROUP_RE.search(c["lines"][0])
    t = unicodedata.normalize("NFD", f'{m.group(1) if m else ""} {c["name"]}')
    t = "".join(ch for ch in t if unicodedata.category(ch) != "Mn").lower()
    if MOVIE_RE.search(t):
        return True
    return bool(CLASSIC_RE.search(t)) and not NOT_MOVIE_RE.search(t)


def check(url, depth=0):
    """Devuelve: 'ok' funciona | 'dead' caído seguro | 'unsure' no se sabe (bloqueo por país,
    tiempo agotado, servidor ocupado…) | 'skip' no verificable (rtmp, udp…)."""
    if not url.lower().startswith("http"):
        return "skip"
    try:
        with get(url) as r:
            ctype = (r.headers.get("Content-Type") or "").lower()
            hls = url.split("?")[0].lower().endswith((".m3u8", ".m3u")) or "mpegurl" in ctype
            data = b""
            limit = 16384 if hls else 188
            while len(data) < limit:
                chunk = r.read(limit - len(data))
                if not chunk:
                    break
                data += chunk
            if not data:
                return "unsure"
            if not hls:
                return "dead" if "text/html" in ctype else "ok"
            txt = data.decode("utf-8", "ignore")
            if "#EXTM3U" not in txt:
                return "dead"
            if depth == 0 and "#EXT-X-STREAM-INF" in txt:
                variant = next((x.strip() for x in txt.splitlines() if x.strip() and not x.startswith("#")), None)
                return check(urllib.parse.urljoin(r.geturl(), variant), 1) if variant else "dead"
            return "ok"
    except urllib.error.HTTPError as e:
        # 404/410: ya no existe en ningún país. 401/403/451/5xx: puede ser bloqueo por país.
        return "dead" if e.code in (404, 410) else "unsure"
    except urllib.error.URLError as e:
        r = e.reason
        # Dominio que no existe = caído en todo el mundo; cualquier otro error de red es dudoso.
        return "dead" if isinstance(r, socket.gaierror) and r.errno == socket.EAI_NONAME else "unsure"
    except Exception:
        return "unsure"


MODOS = ("estricto", "tolerante", "completa")


def main():
    cfg = json.loads(Path(sys.argv[1] if len(sys.argv) > 1 else "config.json").read_text(encoding="utf-8"))
    out_dir = Path(cfg.get("salida", "listas")); out_dir.mkdir(parents=True, exist_ok=True)
    resumen = [f"# Resumen ({datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC)\n",
               "| Lista | Modo | Revisados | Funcionan | Dudosos | Caídos | Publicados |", "|---|---|---|---|---|---|---|"]
    for f in cfg["fuentes"]:
        nombre, modo = f["nombre"], f.get("modo", "tolerante")
        if modo not in MODOS:
            print(f"== {nombre}: modo '{modo}' no válido (usa {', '.join(MODOS)}); se omite.")
            continue
        print(f"== {nombre} [{modo}]: {f['url']}", flush=True)
        try:
            with get(f["url"], 90) as r:
                header, chans = parse(r.read().decode("utf-8", "ignore"))
        except Exception as e:
            print(f"   No se pudo descargar ({e}); se conserva la versión anterior.")
            continue
        if f.get("solo_peliculas"):
            chans = [c for c in chans if is_movie(c)]
        seen, uniq = set(), []
        for c in chans:
            if c["url"] not in seen:
                seen.add(c["url"]); uniq.append(c)
        print(f"   {len(uniq)} canales", flush=True)
        if modo == "completa":
            keep, n_ok, n_unsure, n_dead = uniq, 0, 0, 0
        else:
            with ThreadPoolExecutor(WORKERS) as ex:
                res = list(ex.map(lambda c: check(c["url"]), uniq))
            n_ok, n_dead = res.count("ok"), res.count("dead")
            n_unsure = len(res) - n_ok - n_dead
            allowed = {"ok"} if modo == "estricto" else {"ok", "unsure", "skip"}
            keep = [c for c, r in zip(uniq, res) if r in allowed]
        print(f"   funcionan {n_ok}, dudosos {n_unsure}, caídos {n_dead} -> se publican {len(keep)}", flush=True)
        resumen.append(f"| {nombre} | {modo} | {len(uniq)} | {n_ok} | {n_unsure} | {n_dead} | {len(keep)} |")
        if not keep:
            print("   No quedó ningún canal; no se sobrescribe el archivo anterior.")
            continue
        target = out_dir / f"{nombre}.m3u"
        prev = target.read_text(encoding="utf-8").count("#EXTINF") if target.exists() else 0
        if modo != "completa" and len(keep) < 0.1 * len(uniq) and prev > len(keep):
            print("   Quedaron muy pocos canales (¿falla de red o bloqueo?); se conserva la versión anterior.")
            continue
        body = header + "\n" + "\n".join("\n".join(c["lines"]) for c in keep) + "\n"
        target.write_text(body, encoding="utf-8")
    (out_dir / "resumen.md").write_text("\n".join(resumen) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
