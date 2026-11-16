#!/usr/bin/env python3
"""из CarDeck Запустить извлечение журнала「Повтор модели автомобиля fixtures」。

Использование：
    python3 tools/extract_car_fixtures.py <Файл журнала или каталог>... [-o app/src/test/resources/carplay/fixtures]

вывод：за журнал → <модель автомобиля>__<Имя журнала>.jsonl
  Первая линия meta（модель автомобиля/Источник/Обзор раунда），События затем делятся на слои.：
  - bt слой：{"layer":"bt","round":N,"dir":"rx|tx","hex":"…"}（RAW rx/tx Сращивание витками）
  - tunnel слой：{"layer":"tunnel","dir":"rx","id":…,"hex":"…"}（IAPLINK rx из CSM тело）
               {"layer":"tunnel","dir":"tx","id":…}（туннель tx Только номер сообщения——golden последовательность）

Определение раунда：войти（Приемная сторона подключена/Подключено…RFCOMM Создать）→ конец（LINK closed/SESSION не удалось/
SESSION конец/Оставаться в живых заканчивается）。Только держи 0x1d01 раундов（Раунды, которые не были распознаны, не имеют ценности для воспроизведения.），
и принеси outcome и продолжительность（Тесты воспроизведения контролируют продолжительность и квоту соответственно.）。

fixture Содержит реальный адрес/Название точки доступа——Каталог по умолчанию gitignore，Регистрация подлежит отдельному рассмотрению.（Посмотреть в каталоге README）。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

LINE_RE = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\.(\d\d\d)\] (.*)$")
RAW_RX_RE = re.compile(r"^RAW rx: ((?:[0-9a-f]{2} ?)+)$")
RAW_TX_RE = re.compile(r"^RAW tx: ((?:[0-9a-f]{2} ?)+)$")
LINK_CLOSE_RE = re.compile(r"LINK closed|SESSION не удалось|SESSION конец|Оставаться в живых заканчивается")
ROUND_START_RE = re.compile(r"Приемная сторона подключена|Подключено .+ беспроводной iAP2 услуга")
IDENT_RE = re.compile(r"iap2 rx=0x1d01 Идентификация автомобиля и машины name=(.*?) model=(.*?) manufacturer=(.*)$")
NSD_MODEL_RE = re.compile(r"NSD: TXT .*model=(\S+?),")
TUNNEL_RX_RE = re.compile(r"^IAPLINK: rx 0x([0-9a-f]+) \(.*\) \d+B hex=((?:[0-9a-f]{2} ?)+)")
TUNNEL_TX_RE = re.compile(r"^IAPLINK: туннель tx 0x([0-9a-f]+)")
PHASE1_RE = re.compile(r"Phase 1 Завершить")


def ms_of(m: re.Match) -> int:
    h, mi, s, ms = (int(m.group(i)) for i in range(1, 5))
    return ((h * 60 + mi) * 60 + s) * 1000 + ms


def car_slug(text: str) -> str:
    ident = IDENT_RE.search(text)
    if ident:
        parts = [p.lower() for p in ident.groups()]
        return re.sub(r"[^a-z0-9]+", "_", "_".join(parts)).strip("_")[:48]
    nsd = NSD_MODEL_RE.search(text)
    if nsd:
        return re.sub(r"[^a-z0-9]+", "_", nsd.group(1).lower()).strip("_")[:48]
    return "unknown"


class Round:
    def __init__(self, index: int, start_ms: int):
        self.index = index
        self.start_ms = start_ms
        self.end_ms = start_ms
        self.rx = bytearray()
        self.tx = bytearray()
        self.ids: list[str] = []  # Номер сообщения CSM, появившегося в этом раунде (для идентификации и определения)
        self.phase1 = False
        self.ident_text = ""


def extract(log_path: Path) -> dict | None:
    rounds: list[Round] = []
    current: Round | None = None
    tunnel_rx: list[dict] = []
    tunnel_tx: list[str] = []
    car_id = "unknown"

    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LINE_RE.match(line)
        if not m:
            continue
        body = m.group(5).strip()
        now = ms_of(m)

        ident = IDENT_RE.search(body)
        if ident:
            car_id = car_slug(body)

        if ROUND_START_RE.search(body):
            current = Round(len(rounds), now)
            rounds.append(current)
        if current is None:
            # Журнал начинается с середины: Все еще собираю анонимное колесо.
            current = Round(0, now)
            rounds.append(current)
        current.end_ms = now

        if TUNNEL_RX_RE.match(body):
            t = TUNNEL_RX_RE.match(body)
            tunnel_rx.append(
                {"layer": "tunnel", "dir": "rx", "id": int(t.group(1), 16), "hex": t.group(2).replace(" ", "")}
            )
        else:
            t = TUNNEL_TX_RE.match(body)
            if t:
                tunnel_tx.append(t.group(1))

        rx = RAW_RX_RE.match(body)
        tx = RAW_TX_RE.match(body)
        if rx:
            data = bytes.fromhex(rx.group(1).replace(" ", ""))
            # Усечение бюджета дампа RAW оставит половину строки (на самом деле во всей строке осталось только «ff»): минимальный кадр/маркер равен 6B,
            # Более короткие фрагменты отбрасываются напрямую, чтобы избежать загрязнения потока байтов воспроизведения.
            if len(data) >= 6:
                current.rx.extend(data)
                current.end_ms = now
        elif tx:
            data = bytes.fromhex(tx.group(1).replace(" ", ""))
            if len(data) >= 6:
                current.tx.extend(data)
                current.end_ms = now
        if PHASE1_RE.search(body) and current is not None:
            current.phase1 = True
            current.ident_text = body

    keep = []
    for r in rounds:
        if not r.rx:
            continue
        ident = IDENT_RE.search(r.ident_text) or IDENT_RE.search("")  # идентификатор каждого раунда взят из текста раунда
        raw = bytes(r.rx)
        # Бюджет дампа RAW, усеченный по байтам (старый журнал 512B): точка усечения часто попадает в середину кадра — на границах кадра
        # Обрежьте конечные остаточные кадры и запишите, сохранился ли идентификационный кадр (полный CSM 0x1D01). Определите кадры, которые усечены
        # Раунд может выполнять только воспроизведение на канальном уровне (движок умрет, если он съест битые кадры и будет ждать проверки), и только полные утверждения могут быть сделаны, когда идентифицированные кадры завершены.
        trimmed, truncated, has_ident, has_syn = trim_to_frame_boundary(raw)
        if not ("1d01" in raw.hex() or r.phase1):
            continue  # Идентификация не достигнута: нет значения воспроизведения (раунд чистого отказа при согласовании покрывается одиночным тестом канального уровня)
        if not has_syn:
            continue  # Нет SYN с хорошей проверкой (битое колесо): канальный уровень не может управляться, и нет значения воспроизведения.
        keep.append(
            {
                "layer": "bt",
                "round": r.index,
                "outcome": "phase1" if r.phase1 else "identified",
                "durationMs": r.end_ms - r.start_ms,
                "rxTruncated": truncated,
                "rxHasIdent": has_ident,
                "rxHex": trimmed.hex(),
                "txHex": bytes(r.tx).hex(),
            }
        )
    if not keep and not tunnel_rx:
        return None
    return {"car": car_id, "source": log_path.name, "btRounds": keep, "tunnelRx": tunnel_rx, "tunnelTxIds": tunnel_tx}


MARKER = bytes.fromhex("ff550200ee10")


def trim_to_frame_boundary(data: bytes) -> tuple[bytes, bool, bool]:
    """Возврат（Обрезанный поток байтов, Происходит ли обрезка, Содержит ли неповрежденный кадр 0x1D01 из CSM）。

    「Длина рамы полная」не равен「Байты нетронуты」：RAW Усечение бюджета дампа по байтам будет иметь значение read из
    Хвосты присоединяются к одному потоку，Выковать раму ровно на всю длину——Контрольная сумма полезной нагрузки не удалась，Двигатель будет следовать протоколу
    молча отбросить（2026-10-20 Воспроизведение фактического теста отладки：Воспроизведение останавливается на 0x1D00 первопричина）。Так что нажимайте покадрово
    Проверка того же алгоритма двигателя（Заголовок кадра 9B Сумма 0；нагрузка+Хвостовая контрольная сумма 0），Первый, кто пройдет
    Обрезайте все от кадра к кадру——Оставшийся механизм потоковой передачи должен принимать его покадрово.。
    """
    off = 0
    has_ident = False
    has_syn = False
    while off < len(data):
        if data[off : off + 6] == MARKER:
            off += 6
            continue
        if off + 4 <= len(data) and data[off] == 0xFF and data[off + 1] == 0x5A:
            frame_len = (data[off + 2] << 8) | data[off + 3]
            if frame_len < 9 or off + frame_len > len(data):
                break  # Неполная длина (ограничение бюджета)
            frame = data[off : off + frame_len]
            if sum(frame[:9]) & 0xFF != 0:
                break  # Заголовок кадра проверить невозможно: сращивание кованых кадров
            if frame_len > 9 and sum(frame[9:frame_len]) & 0xFF != 0:
                break  # Полезную нагрузку невозможно проверить: то же, что и выше.
            if frame_len > 9:
                payload = frame[9 : frame_len - 1]
                if frame[4] & 0x80:  # ctl с битом SYN (пакет синхронизации с таблицей сеансов)
                    has_syn = True
                if payload[:2] == b"\x40\x40" and payload[4:6] == b"\x1d\x01":
                    has_ident = True
            off += frame_len
            continue
        break  # Нераспознанный байт: стоп (консервативно отбросить хвост)
    return data[:off], off < len(data), has_ident, has_syn


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("-o", "--out", default="app/src/test/resources/carplay/fixtures")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for raw_path in args.inputs:
        p = Path(raw_path)
        files = [p] if p.is_file() else sorted(p.glob("CarDeck-log-*.txt")) if p.is_dir() else []
        for f in files:
            data = extract(f)
            if data is None:
                print(f"skip（Нет контента для повторов）: {f.name}")
                continue
            target = out_dir / f"{data['car']}__{f.stem}.jsonl"
            with target.open("w", encoding="utf-8") as w:
                w.write(json.dumps({"meta": data["meta"] if "meta" in data else {k: data[k] for k in ("car", "source")}}, ensure_ascii=False) + "\n")
                for r in data["btRounds"]:
                    w.write(json.dumps(r, ensure_ascii=False) + "\n")
                for e in data["tunnelRx"]:
                    w.write(json.dumps(e, ensure_ascii=False) + "\n")
                for tid in data["tunnelTxIds"]:
                    w.write(json.dumps({"layer": "tunnel", "dir": "tx", "id": int(tid, 16)}, ensure_ascii=False) + "\n")
            print(f"wrote {target.name}（btколесо {len(data['btRounds'])}，tunnel rx {len(data['tunnelRx'])}）")
            written += 1
    print(f"Завершить：{written} а fixture → {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
