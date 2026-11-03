"""Merge LingQ sentences that were wrongly split at abbreviations (Abb., ca., hl., 19. Jahrhundert ...).

The text itself is not changed: sentences are merged in the lesson's editor structure
(paragraphs -> sentences) together with their translations, and saved back via PATCH.
Also cleans up lookalike dots (U+2024) and markdown escapes like \\[sei\\].

Usage (TARGET = course/lesson URL, or a numeric course id):
  python lingq_fix_splits.py TARGET                    # dry run: backup + report, nothing is written
  python lingq_fix_splits.py TARGET --apply            # write changes to LingQ
  python lingq_fix_splits.py COURSE --lesson ID ...    # only these lessons of the course
  python lingq_fix_splits.py --restore runs/<stamp>/backup/<id>.json   # put a lesson back

  e.g. https://www.lingq.com/ru/learn/de/web/library/course/2951278
       https://www.lingq.com/ru/learn/de/web/lesson/47481004

Each run writes runs/<stamp>/report.md and runs/<stamp>/backup/<lesson id>.json.
API key: env var LINGQ_API_KEY, or file lingq_key.txt next to this script.
"""
import argparse, datetime, json, os, re, sys, time, urllib.request, urllib.error

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
LANG = "de"   # default language; overridden by the URL or --lang
API = "https://www.lingq.com/api/v3"
KEY = os.environ.get("LINGQ_API_KEY", "").strip()
if not KEY and os.path.exists(os.path.join(HERE, "lingq_key.txt")):
    KEY = open(os.path.join(HERE, "lingq_key.txt"), encoding="utf-8").read().strip()
if not KEY:
    sys.exit("No API key: set LINGQ_API_KEY (get it at https://www.lingq.com/accounts/apikey/)")
# A custom User-Agent is required: Cloudflare rejects Python's default one (error 1010).
HEADERS = {"Authorization": "Token " + KEY, "Accept": "application/json",
           "User-Agent": "lingq-lesson-fixer/1.0"}

# Words that end with a dot but do not end a sentence. Compared case-sensitively against
# the last "word." of a sentence. Extend as needed.
ABBREVIATIONS = {
    "Abb", "Abbn", "Taf", "Kat", "Kap", "Bd", "Bde", "Nr", "Inv", "Inv.-Nr", "Sign", "Slg", "Rar",
    "Ink", "fol", "Fol", "Bl", "Dm", "Hrsg", "hrsg", "Aufl", "Anm", "vgl", "Vgl", "s", "S", "Sp",
    "ca", "Ca", "hl", "Hl", "St", "Dr", "Prof", "bzw", "usw", "etc", "evtl", "ggf", "sog", "u",
    "z", "B", "d", "Ä", "J", "Jh", "Jhs", "Jhdt", "geb", "gest", "verh", "röm", "lat", "griech",
    "dt", "frz", "ital", "mhd", "ahd", "Mio", "Mrd", "Tsd", "v", "Chr", "n", "a", "o", "ff", "f",
    "ders", "Ders", "Hg", "hg", "ebd", "Ebd", "zit", "Zit", "Gr", "A", "Nachf", "Bibl",
    "Mus", "Stadtbibl", "Staatsbibl", "Univ", "Pl", "Abt", "Ms", "Cod", "Cgm", "Clm", "Hs",
}
LAST_TOKEN = re.compile(r"(\S+)\.$")

# Words that typically START a new sentence. After an ordinal ("3.") or a single letter ("A.")
# we only merge if the next fragment does NOT begin with one of these:
#   "im 19. | Jahrhundert" -> merge;  "zum Typ 3. | Und Rover ..." -> keep apart.
SENTENCE_STARTERS = set("""
der die das den dem des ein eine einer eines einem einen und aber doch oder denn sondern so also
dann da danach daher deshalb deswegen trotzdem außerdem auch noch nun jetzt heute hier dort
es er sie ich wir ihr man wer was wie wo wann warum wenn als weil dass ob obwohl während nachdem
bevor seit bis mit in im am an auf aus bei nach von vor zu zum zur für über unter durch gegen ohne
um dieser diese dieses diesen diesem jener jene jedes jeder jede alle viele einige manche kein keine
nicht nur schon immer zwar ja nein na naja okay ok gut genau tja mein meine sein seine ihre unser
dabei damit darauf darum dafür dazu davon hinzu zudem zunächst schließlich später erst sogar
""".split())


def req(method, url, data=None, tries=5):
    body = json.dumps(data).encode() if data is not None else None
    h = dict(HEADERS)
    if body:
        h["Content-Type"] = "application/json"
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=h, method=method),
                                        timeout=120) as r:
                t = r.read().decode()
                return json.loads(t) if t.strip() else None
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                wait = int(e.headers.get("Retry-After") or 5 * (attempt + 1))
                print(f"   HTTP {e.code}, retry in {wait}s")
                time.sleep(wait)
                continue
            raise RuntimeError(f"{method} {url} -> HTTP {e.code}: {e.read().decode()[:300]}")
    raise RuntimeError(f"{method} {url}: too many retries")


def first_word(text):
    m = re.match(r"[\W\d_]*([^\W\d_]+)", text)
    return m.group(1).lower() if m else ""


def ends_with_abbreviation(text, nxt=""):
    m = LAST_TOKEN.search(text.rstrip())
    if not m:
        return False
    tok = m.group(1).lstrip("([«„\"'")
    if tok in ABBREVIATIONS:                    # also covers "d. Ä." / "z. B." / "Inv.-Nr."
        return True
    # ordinal ("19." Jahrhundert, "21." Mai), initials / roman numerals ("Maximilian I.", "F.A.Z."):
    # ambiguous, a real sentence can end like that ("... zum Typ 3."), so look at what follows
    if re.fullmatch(r"\d{1,2}|[A-ZÄÖÜ]|[IVX]{1,4}|([A-ZÄÖÜ]\.)+[A-ZÄÖÜ]", tok):
        if nxt.lstrip().startswith("("):        # "Theodosius I. (379–395)"
            return True
        return first_word(nxt) not in SENTENCE_STARTERS
    return False


def should_merge(prev, nxt):
    p, n = prev.rstrip(), nxt.lstrip()
    if not n:
        return False
    if ends_with_abbreviation(p, n):
        return True
    if n[0] in ")]»,;:" or n[0].islower():      # next one can't start a sentence
        return True
    if p.endswith(("?", "!")) and p.count("(") > p.count(")"):   # "(?" split inside brackets
        return True
    return False


def join_text(a, b):
    b = b.lstrip()
    return a.rstrip() + ("" if b[:1] in ")]»,;:" else " ") + b


def merge_sentences(a, b):
    m = dict(a)
    m["text"] = join_text(a["text"], b["text"])
    m["cleanText"] = join_text(a.get("cleanText", a["text"]), b.get("cleanText", b["text"]))
    m["phrases"] = (a.get("phrases") or []) + (b.get("phrases") or [])
    m["notes"] = (a.get("notes") or []) + (b.get("notes") or [])
    tr = {}
    for t in (a.get("translations") or []) + (b.get("translations") or []):
        key = t.get("language")
        if key in tr:
            tr[key] = dict(tr[key], text=join_text(tr[key]["text"], t["text"]))
        else:
            tr[key] = dict(t)
    m["translations"] = list(tr.values())
    ts_a, ts_b = a.get("timestamp") or [None, None], b.get("timestamp") or [None, None]
    m["timestamp"] = [ts_a[0], ts_b[1] if ts_b[1] is not None else ts_a[1]]
    return m


def norm(t):
    return re.sub(r"\s+", " ", t).strip()


def clean_text(t):
    t = t.replace(chr(0x2024), ".")              # lookalike dot -> real dot
    for ch in "[]*_":                            # markdown escapes like \[sei\] -> [sei]
        t = t.replace(chr(92) + ch, ch)
    return t


def fix_paragraphs(paragraphs):
    changes = []
    for p in paragraphs:
        for s in p["sentences"]:
            for k in ("text", "cleanText"):
                if k in s and clean_text(s[k]) != s[k]:
                    if k == "text":
                        changes.append(("cleanup", s[k], ""))
                    s[k] = clean_text(s[k])
        out = []
        for s in p["sentences"]:
            if out and len(norm(out[-1]["text"])) > 10 and norm(s["text"]).startswith(norm(out[-1]["text"])):
                changes.append(("dup", out[-1]["text"], ""))
                out[-1] = s                         # LingQ duplicated a fragment: keep the full one
            elif out and should_merge(out[-1]["text"], s["text"]):
                changes.append(("merge", out[-1]["text"], s["text"]))
                out[-1] = merge_sentences(out[-1], s)
            else:
                out.append(s)
        p["sentences"] = out
    i = 0
    for p in paragraphs:
        for s in p["sentences"]:
            i += 1
            s["index"] = i
    return changes


def lesson_list(course):
    url = f"{API}/{LANG}/collections/{course}/lessons/?page=1&page_size=100&sortBy=pos"
    res = []
    while url:
        d = req("GET", url)
        res += d["results"]
        url = d.get("next")
    return res


def parse_target(target, lang):
    """Return (lang, course_id, lesson_id) from a LingQ URL or a bare course id."""
    if target.isdigit():
        return lang, int(target), None
    m = re.search(r"/learn/([a-z]{2,3}(?:-[a-z]+)?)/", target)
    if m:
        lang = m.group(1)
    m = re.search(r"/course/(\d+)", target) or re.search(r"/collections?/(\d+)", target)
    if m:
        return lang, int(m.group(1)), None
    m = re.search(r"/(?:lesson|reader|lessons|editor)/(\d+)", target)
    if m:
        return lang, None, int(m.group(1))
    sys.exit(f"Cannot understand target: {target}")


def main():
    global LANG
    ap = argparse.ArgumentParser()
    ap.add_argument("target", nargs="?", help="course/lesson URL or course id")
    ap.add_argument("--apply", action="store_true", help="write changes to LingQ")
    ap.add_argument("--lesson", type=int, nargs="*", help="only these lesson ids of the course")
    ap.add_argument("--lang", default=LANG, help="language code if the target is a bare id")
    ap.add_argument("--restore", help="backup json to put back")
    a = ap.parse_args()

    if a.restore:
        b = json.load(open(a.restore, encoding="utf-8"))
        lang = b.get("lang", a.lang)
        req("PATCH", f"{API}/{lang}/lessons/{b['id']}/", {"paragraphs": b["paragraphs"]})
        print("restored", b["id"], b["title"])
        return
    if not a.target:
        ap.error("target is required")

    LANG, course, lesson = parse_target(a.target, a.lang)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    rdir = os.path.join(HERE, "runs", stamp)
    bdir = os.path.join(rdir, "backup")
    os.makedirs(bdir, exist_ok=True)

    out = []

    def md(t):
        for ch in chr(92) + "`*_[]<>|#":         # escape markdown specials (backslash first)
            t = t.replace(ch, chr(92) + ch)
        return t

    def tail(t, n=55):
        t = norm(t)
        return ("…" if len(t) > n else "") + t[-n:]

    def head(t, n=55):
        t = norm(t)
        return t[:n] + ("…" if len(t) > n else "")

    if lesson:
        d = req("GET", f"{API}/{LANG}/lessons/{lesson}/")
        lessons = [{"id": lesson, "title": d["title"]}]
    else:
        lessons = lesson_list(course)
        if a.lesson:
            lessons = [l for l in lessons if l["id"] in a.lesson]
    total, touched, untouched = 0, 0, []
    print(f"{'APPLY' if a.apply else 'DRY RUN'} [{LANG}] {lesson or course}, {len(lessons)} lessons")
    for l in lessons:
        ed = req("GET", f"{API}/{LANG}/lessons/{l['id']}/editor/")
        json.dump({"id": l["id"], "lang": LANG, "title": l["title"], "paragraphs": ed["paragraphs"]},
                  open(os.path.join(bdir, f"{l['id']}.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
        paras = json.loads(json.dumps(ed["paragraphs"]))
        changes = fix_paragraphs(paras)
        print(f"  {l['id']}  {l['title'][:60]}  -> {len(changes)}")
        if not changes:
            untouched.append(l["title"])
            time.sleep(0.5)
            continue
        total += len(changes)
        touched += 1
        merges = [c for c in changes if c[0] == "merge"]
        dups = [c for c in changes if c[0] == "dup"]
        cleanups = [c for c in changes if c[0] == "cleanup"]
        url = f"https://www.lingq.com/{LANG}/learn/{LANG}/web/lesson/{l['id']}"
        out.append(f"### [{md(l['title'])}]({url}): {len(merges)} склеек")
        for _, x, y in merges:
            out.append(f"- {md(tail(x))} **‖** {md(head(y))}")
        for _, x, _ in dups:
            out.append(f"- 🗑 убран повтор: _{md(head(x, 80))}_")
        if cleanups:
            out.append(f"- 🔧 исправлены символы (․ → . , \\[ → [) в {len(cleanups)} предложениях")
        if a.apply:
            req("PATCH", f"{API}/{LANG}/lessons/{l['id']}/", {"paragraphs": paras})
            out.append("- ✅ **записано в LingQ**")
            time.sleep(2)
        out.append("")
        time.sleep(0.5)

    title = "✅ Записано в LingQ" if a.apply else "🔍 Пробный прогон: в LingQ ничего не изменено"
    what = f"урок {lesson}" if lesson else f"курс {course}"
    hdr = [f"## {title}", "",
           f"{what} · уроков: {len(lessons)} · с правками: {touched} · всего правок: {total}", ""]
    if total and not a.apply:
        hdr += ["**Как читать:** каждая строка показывает место, где LingQ разрезал одно предложение на два. "
                "**‖** означает место будущей склейки. Слева конец первого куска, справа начало второго. "
                "Если по обе стороны от **‖** на самом деле два разных предложения, это ошибка, напишите о ней.",
                "Если всё в порядке, запустите ещё раз с режимом **apply**.", ""]
    foot = []
    if untouched:
        foot += ["", f"<details><summary>Без изменений: {len(untouched)} уроков</summary>", ""]
        foot += [f"- {md(t)}" for t in untouched]
        foot += ["", "</details>"]
    foot += ["", f"Резервная копия уроков: `{os.path.relpath(bdir, HERE)}`"]
    open(os.path.join(rdir, "report.md"), "w", encoding="utf-8").write("\n".join(hdr + out + foot) + "\n")
    print(f"\nTotal changes: {total}. Report: {os.path.relpath(rdir, HERE)}/report.md")


if __name__ == "__main__":
    main()
