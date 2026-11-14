#!/usr/bin/env python3
"""websearch - the web-search agent for omalauncher's prompt bar.

It decides whether what was typed is a link, a bookmark or something to search
for, and turns it into the one URL to open: a search on the chosen engine, the
link itself, or the bookmark's page, opened the way the chosen browser wants it.

    websearch <text>...            open <text> (or print it, without a browser)
    websearch open <text>...       resolve it and open it
    websearch print <text>...      print only the URL to open, for the launcher
    websearch explain <text>...    what it would do, in a line
    websearch kind <text>...       url, bookmark or search
    websearch engines              the search engines it knows
    websearch defaults             the engines and the default settings, as JSON

    websearch browsers             the browsers it knows, as JSON
    websearch browser set PKG KEY VALUE
                                  KEY is name, open or private; open and private
                                  are link templates with {url}, {urlenc} and
                                  {urlbare} ({url} without https://)
    websearch browser remove PKG

    websearch bookmarks            your bookmarks, one "name<TAB>url" a line
    websearch bookmarks json       the same, as JSON
    websearch bookmark add URL [NAME...]
    websearch bookmark remove URL-or-name
    websearch bookmark import FILE...   a browser's exported bookmarks (HTML or
                                  Chrome's JSON), or --pick for Android's picker

    websearch config               the settings, as JSON
    websearch config path          where the settings are
    websearch config get KEY       one setting
    websearch config set KEY VALUE
    websearch config reset         back to the defaults

Settings live in ~/.config/omalauncher/websearch.json, which the launcher's
"web search" app edits: which engine, which browser and whether privately, a
rule per site ("host open", "host search", "host open private brave") and the
bookmarks.

Android opens a link in its default browser and nowhere else, unless that
browser has a link scheme of its own: then the template for it is used. The
settings app lists the browsers installed, tests a template, and opens a
browser's Android settings to make it the default where nothing else works;
the drawer's search row shares the link to Android's share sheet for the rest.
"""

import json
import os
import re
import subprocess
import sys
import urllib.parse

CONFIG = os.path.expanduser("~/.config/omalauncher/websearch.json")

# id -> (name, search URL; {q} is the query, percent-encoded)
ENGINES = [
    ("brave", "Brave Search", "https://search.brave.com/search?q={q}"),
    ("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q={q}"),
    ("google", "Google", "https://www.google.com/search?q={q}"),
    ("startpage", "Startpage", "https://www.startpage.com/sp/search?query={q}"),
    ("kagi", "Kagi", "https://kagi.com/search?q={q}"),
    ("ecosia", "Ecosia", "https://www.ecosia.org/search?q={q}"),
    ("qwant", "Qwant", "https://www.qwant.com/?q={q}"),
    ("mojeek", "Mojeek", "https://www.mojeek.com/search?q={q}"),
    ("marginalia", "Marginalia", "https://search.marginalia.nu/search?query={q}"),
    ("wikipedia", "Wikipedia", "https://en.wikipedia.org/w/index.php?search={q}"),
    ("youtube", "YouTube", "https://www.youtube.com/results?search_query={q}"),
]
ENGINE_URLS = {i: u for i, _, u in ENGINES}

DEFAULTS = {
    "engine": "brave",
    # A search URL of your own, with {q} where the query goes; "" uses the engine above.
    "engine_url": "",
    # "system" opens the Android default browser; a package like "com.brave.browser"
    # opens that browser through the link template bmelow, where it has one.
    "browser": "system",
    "private": False,
    # A bare "example.com" counts as a link rather than words to search for.
    "bare_links": True,
    # package -> {name, open, private}: link templates for a browser of its own.
    # {url} is the link as it is, {urlenc} the same percent-encoded, and
    # {urlbare} the link without its https:// (a scheme that takes a URL as its path).
    "browsers": {},
    # One rule per line: "host open", "host search", "host open private",
    # "host open private brave", "*.host search".
    "rules": "",
    # [{name, url}], oldest first.
    "bookmarks": [],
}

# The browsers it knows by package: name, a link template if the browser takes
# one, and a private-tab template if it has one. Android opens a link in the
# default browser and nowhere else unless that browser registers a scheme of its
# own; chromium's stable manifest registers googlechrome://, and the browsers
# built on it keep that filter, which is why they are here. Firefox's family has
# no such scheme, so those open only as Android's default: the settings app
# offers its app info instead, and the drawer's chooser shares the link.
# Nothing on Android opens a private tab from outside the browser, so no
# private template is filled in: a browser that is private by design (Tor) is
# the way to keep a site out of a normal tab.
KNOWN = {
    "com.android.chrome": ("Chrome", "googlechrome://{urlbare}", ""),
    "com.brave.browser": ("Brave", "googlechrome://{urlbare}", ""),
    "com.microsoft.emmx": ("Edge", "googlechrome://{urlbare}", ""),
    "com.opera.browser": ("Opera", "googlechrome://{urlbare}", ""),
    "com.vivaldi.browser": ("Vivaldi", "googlechrome://{urlbare}", ""),
}

# A bare link's last label has to be one of these, so "notes.txt" is searched for
# and "example.dev" is opened. Two-letter country codes are taken as they come.
TLDS = set(
    """com org net edu gov mil int io dev app ai co me xyz info news blog page site online
    store tech cloud systems network digital live life world today space zone ninja guru rocks
    email social wiki link click shop art design studio games fun host press chat video music
    photo pics travel academy agency coffee computer consulting digital directory download
    engineering enterprises equipment estate events exchange expert exposed express finance
    financial fish fitness flights florist football forsale foundation gallery garden gifts
    glass global gmbh graphics group guide healthcare help hiphop hockey holdings holiday homes
    house immobilier industries ink institute insure international investments jewelry kitchen
    land law lawyer lease legal lgbt limited limo loan loans ltd luxury management market
    marketing media memorial men menu mobi moda money mortgage movie museum navy ngo observer
    org partners parts parts photo pizza place plumbing plus poker productions promo properties
    pub racing recipes red rehab reisen rentals repair report republican rest restaurant review
    reviews rip rocks rodeo sale salon school science services shoes show singles ski soccer
    solar solutions space storage supply support surf surgery systems taxi team tennis theater
    theatre tips tires tools tours town toys trade training university vacations ventures vet
    viajes video villas vision vodka vote voting voyage watch webcam website wedding wiki win
    wine work works wtf yoga""".split()
)
CC = set(
    """ac ad ae af ag ai al am ao aq ar as at au aw ax az ba bb bd be bf bg bh bi bj bm bn bo
    br bs bt bw by bz ca cc cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz de dj dk dm do
    dz ec ee eg er es et eu fi fj fk fm fo fr ga gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu
    gw gy hk hm hn hr ht hu id ie il im in io iq ir is it je jm jo jp ke kg kh ki km kn kp kr
    kw ky kz la lb lc li lk lr ls lt lu lv ly ma mc md me mg mh mk ml mm mn mo mp mq mr ms mt
    mu mv mw mx my mz na nc ne nf ng ni nl no np nr nu nz om pa pe pf pg ph pk pl pm pn pr ps
    pt pw py qa re ro rs ru rw sa sb sc sd se sg sh si sk sl sm sn so sr ss st sv sx sy sz tc
    td tf tg th tj tk tl tm tn to tr tt tv tw tz ua ug uk us uy uz va vc ve vg vi vn vu wf ws
    ye yt za zm zw""".split()
)
# A file that happens to end like a country code is still a file: "notes.md" is
# a document, not Moldova, and a shell script isn't Saint Helena.
FILE_LIKE = {
    "md", "sh", "py", "js", "ts", "rb", "rs", "go", "c", "h", "cc", "cpp", "java", "kt",
    "json", "toml", "yaml", "yml", "ini", "cfg", "conf", "txt", "log", "csv", "pdf", "png",
    "jpg", "jpeg", "gif", "webp", "svg", "mp3", "mp4", "mov", "zip", "tar", "gz", "xz", "deb",
    "apk", "iso", "owl", "lock", "env", "bak", "tmp", "old",
}

SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*:")
LABEL = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9\-_]*[A-Za-z0-9])?$")
IPV4 = re.compile(r"^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?(?:[/?#]|$)")
LOCAL = re.compile(r"^(localhost|127\.0\.0\.1|\[::1\])(?::(\d{1,5}))?(?:[/?#]|$)", re.I)
WEB_SCHEMES = {"http", "https"}


def load():
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG, "r", encoding="utf-8") as f:
            saved = json.load(f)
        if isinstance(saved, dict):
            cfg.update({k: v for k, v in saved.items() if k in DEFAULTS})
    except FileNotFoundError:
        pass
    except (OSError, ValueError):
        # A broken file shouldn't stop the bar from searching: defaults do.
        pass
    if not isinstance(cfg.get("browsers"), dict):
        cfg["browsers"] = {}
    if not isinstance(cfg.get("bookmarks"), list):
        cfg["bookmarks"] = []
    return cfg


def save(cfg):
    out = {k: cfg[k] for k in DEFAULTS}
    os.makedirs(os.path.dirname(CONFIG), exist_ok=True)
    tmp = CONFIG + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
        f.write("\n")
    os.replace(tmp, CONFIG)


def engine_template(cfg):
    own = str(cfg.get("engine_url") or "").strip()
    if own:
        return own
    return ENGINE_URLS.get(str(cfg.get("engine") or ""), ENGINE_URLS["brave"])


def search_url(cfg, text):
    template = engine_template(cfg)
    q = urllib.parse.quote_plus(text)
    # {q} is the query encoded for a URL; a template may write the query into a
    # path with {qpath}, where a space is %20 rather than a plus.
    return template.replace("{q}", q).replace("{qpath}", urllib.parse.quote(text, safe=""))


def tld_ok(last):
    last = last.lower()
    if last in FILE_LIKE:
        return False
    return last in TLDS or last in CC


def split_bare(text, bare_links=True):
    """(host, local) of a link written without a scheme, or (None, False).

    "example.com", "www.example.com/a?b", "localhost:3000", "192.168.0.2:8080/x".
    localhost, an IP and a .local name are always links; a bare domain only when
    settings say so.
    """
    if not text or any(c.isspace() for c in text):
        return None, False
    if LOCAL.match(text):
        return text.split("/")[0].split("?")[0].split("#")[0].lower(), True
    ip = IPV4.match(text)
    if ip and all(0 <= int(g) <= 255 for g in ip.groups()[:4]):
        return text.split("/")[0].split("?")[0].split("#")[0], True
    if not bare_links:
        return None, False
    rest = re.split(r"[/?#]", text, maxsplit=1)[0]
    if not rest or "@" in rest:
        return None, False
    host, _, port = rest.partition(":")
    if port and not port.isdigit():
        return None, False
    host = host.rstrip(".")
    if not host or len(host) > 253:
        return None, False
    labels = host.split(".")
    if len(labels) < 2 and not host.lower().startswith("www."):
        return None, False
    if not all(labels) or not all(LABEL.match(l) for l in labels):
        return None, False
    # A name on your own network, like a printer's, is a link whatever its last label.
    if labels[-1].lower() != "local" and not tld_ok(labels[-1]):
        return None, False
    return host.lower(), host.lower().endswith(".local")


def normalize(text, bare_links=True):
    """(link, host) when the text is a link, else (None, None)."""
    text = text.strip()
    # A link never has a space in it: "note: buy milk" is words, not a scheme.
    if not text or any(c.isspace() for c in text):
        return None, None
    host, local = split_bare(text, bare_links)
    if host:
        return ("http://" if local else "https://") + text, host
    if SCHEME.match(text):
        scheme = text.split(":", 1)[0].lower()
        if scheme in WEB_SCHEMES:
            try:
                host = urllib.parse.urlsplit(text).hostname or ""
            except ValueError:
                return None, None
            return text, host.lower()
        # tel:, mailto:, geo:, magnet:, an app's own scheme: passed along as written.
        return text, ""
    return None, None


# --- browsers -----------------------------------------------------------------


def browser_entry(cfg, package):
    """(name, open template, private template) for a package: settings first."""
    mine = (cfg.get("browsers") or {}).get(package) or {}
    known = KNOWN.get(package, ("", "", ""))
    name = str(mine.get("name") or known[0] or package)
    open_t = str(mine.get("open") if mine.get("open") is not None else known[1] or "").strip()
    private_t = str(mine.get("private") if mine.get("private") is not None else known[2] or "").strip()
    return name, open_t, private_t


def browser_list(cfg):
    out = []
    for package in sorted(set(KNOWN) | set(cfg.get("browsers") or {})):
        name, open_t, private_t = browser_entry(cfg, package)
        out.append({
            "package": package,
            "name": name,
            "open": open_t,
            "private": private_t,
            "known": package in KNOWN,
        })
    return out


def find_browser(cfg, word):
    """The package a rule's or setting's word names, or None for "system"."""
    word = (word or "").strip()
    if not word or word.lower() in ("system", "default", "android"):
        return "system"
    if word in KNOWN or word in (cfg.get("browsers") or {}):
        return word
    low = word.lower()
    for package in sorted(set(KNOWN) | set(cfg.get("browsers") or {})):
        name, _, _ = browser_entry(cfg, package)
        if low == name.lower() or low == package.lower():
            return package
    return None


def fill_template(template, url):
    bare = re.sub(r"^[A-Za-z][A-Za-z0-9+.\-]*://", "", url)
    return (template.replace("{urlenc}", urllib.parse.quote(url, safe=""))
            .replace("{urlbare}", bare)
            .replace("{url}", url))


def browser_target(url, cfg, rule, package=None):
    """[url] as the chosen browser takes it, whether it's private, and its name."""
    asked_browser = package or ((rule or {}).get("browser"))
    if asked_browser is None:
        asked_browser = str(cfg.get("browser") or "system")
    package = find_browser(cfg, asked_browser)
    if package is None:
        package = "system"
    wanted = (rule or {}).get("private")
    wanted = bool(cfg.get("private")) if wanted is None else wanted
    name = "Android default"
    template = ""
    private_template = ""
    if package != "system":
        name, template, private_template = browser_entry(cfg, package)
    if not template:
        # No link scheme: only the default browser can open it, and it can't be
        # asked for a private tab.
        return url, False, name, False
    private = bool(wanted and private_template)
    return fill_template(private_template if private else template, url), private, name, True


# --- rules --------------------------------------------------------------------

def parse_rules(cfg):
    rules = []
    for line in str(cfg.get("rules") or "").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        words = line.split()
        rule = {"match": words[0].lower().lstrip("."), "action": "open", "private": None, "browser": None}
        for w in words[1:]:
            low = w.lower()
            if low in ("open", "search"):
                rule["action"] = low
            elif low in ("private", "public", "incognito"):
                rule["private"] = low.startswith(("private", "incognito"))
            elif low != "system":
                rule["browser"] = w
            else:
                rule["browser"] = "system"
        if rule["match"].startswith("*."):
            rule["match"] = rule["match"][2:]
            rule["subdomains"] = True
        rules.append(rule)
    return rules


def rule_for(host, rules):
    if not host:
        return None
    for r in rules:
        m = r["match"]
        if host == m or (r.get("subdomains") and host.endswith("." + m)):
            return r
    return None


# --- bookmarks ----------------------------------------------------------------

def bookmark_from(cfg, text):
    """The bookmark whose name is [text], or None; also by its host and path."""
    low = text.strip().lower()
    if not low:
        return None
    for b in cfg.get("bookmarks") or []:
        if str(b.get("name") or "").strip().lower() == low:
            return b
    for b in cfg.get("bookmarks") or []:
        url = str(b.get("url") or "")
        bare = re.sub(r"^https?://(www\.)?", "", url).rstrip("/").lower()
        if bare == low.rstrip("/"):
            return b
    return None


def add_bookmark(cfg, url, name=""):
    url = url.strip()
    if not url:
        return None
    if not SCHEME.match(url) and split_bare(url, True)[0]:
        url = "https://" + url
    name = name.strip() or url.split("//", 1)[-1].split("/")[0].removeprefix("www.")
    marks = [b for b in cfg.get("bookmarks") or [] if str(b.get("url")) != url]
    marks.append({"name": name, "url": url})
    cfg["bookmarks"] = marks
    return name


def parse_bookmarks(path):
    """(name, url) pairs from a browser's export: netscape HTML, Chrome JSON or text."""
    with open(path, "rb") as f:
        raw = f.read(4 * 1024 * 1024)
    text = raw.decode("utf-8", "replace")
    out = []
    stripped = text.lstrip()
    if stripped.startswith("{") or stripped.startswith("["):
        try:
            data = json.loads(stripped)
        except ValueError:
            data = None

        def walk(node):
            if isinstance(node, dict):
                if node.get("type") == "url" and node.get("url"):
                    out.append((str(node.get("name") or ""), str(node["url"])))
                for v in node.values():
                    walk(v)
            elif isinstance(node, list):
                for v in node:
                    walk(v)

        if data is not None:
            walk(data)
            return out
    if "<a " in text.lower() or "<dt>" in text.lower():
        for m in re.finditer(r'<a\s+[^>]*href\s*=\s*"([^"]+)"[^>]*>(.*?)</a>', text, re.I | re.S):
            url = m.group(1).strip()
            name = re.sub(r"<[^>]+>", "", m.group(2))
            name = (name.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", '"').replace("&#39;", "'").strip())
            if url.startswith(("http://", "https://", "ftp://")):
                out.append((name, url))
        return out
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t") if "\t" in line else line.split(None, 1)
        if len(parts) == 2 and (parts[0].startswith("http") or parts[1].startswith("http")):
            if parts[1].startswith("http"):
                out.append((parts[0], parts[1]))
            else:
                out.append((parts[1], parts[0]))
        elif SCHEME.match(line):
            out.append(("", line))
    return out


def import_bookmarks(cfg, paths):
    added = 0
    for path in paths:
        try:
            pairs = parse_bookmarks(path)
        except OSError as e:
            print("websearch: %s: %s" % (path, e.strerror or e), file=sys.stderr)
            continue
        for name, url in pairs:
            if not url.lower().startswith(("http://", "https://")):
                continue
            before = len(cfg["bookmarks"])
            add_bookmark(cfg, url, name)
            if len(cfg["bookmarks"]) > before:
                added += 1
    save(cfg)
    return added


# --- resolving ----------------------------------------------------------------

def resolve(text, cfg, package=None):
    """The URL to open and a line saying how it was chosen."""
    text = " ".join(text).strip()
    if not text:
        return "", "nothing typed"
    link, host = normalize(text, bool(cfg.get("bare_links")))
    mark = None if link else bookmark_from(cfg, text)
    if mark:
        link, host = normalize(str(mark.get("url")), True)
        if not link:
            return "", "a bookmark without a link"
    if not link:
        template = engine_template(cfg)
        name = next((n for i, n, u in ENGINES if u == template), None) or "your engine"
        return search_url(cfg, text), "search · %s · %s" % (name, text)
    rule = rule_for(host, parse_rules(cfg))
    if rule and rule["action"] == "search":
        return search_url(cfg, text), "search · a rule for %s · %s" % (host, text)
    target, private, where, scheme = browser_target(link, cfg, rule, package)
    asked = (rule or {}).get("private")
    asked = bool(cfg.get("private")) if asked is None else asked
    how = "private" if private else "browser"
    if asked and not private:
        how += " (that browser can't open privately)"
    if not scheme:
        where = "Android default"
    by = " · a bookmark" if mark else (" · a rule for %s" % host if rule else "")
    return target, "open · %s · %s%s · %s" % (how, where, by, link)


def open_target(target):
    if not target:
        return 1
    if SCHEME.match(target) and target.split(":", 1)[0].lower() not in WEB_SCHEMES:
        # xdg-open here only takes web links; the launcher's app opens the rest.
        print(target)
        return 0
    try:
        subprocess.run(["xdg-open", target], check=False, timeout=10)
    except (OSError, subprocess.SubprocessError):
        print(target)
        return 1
    return 0


def usage():
    print(__doc__.strip(), file=sys.stderr)
    return 2


def main(argv):
    if not argv:
        return usage()
    cmd, rest = argv[0], argv[1:]
    cfg = load()

    # --browser PKG and --private/--public, for one call only.
    package = None
    forced = None
    words = []
    i = 0
    while i < len(rest):
        a = rest[i]
        if a == "--browser" and i + 1 < len(rest):
            package = rest[i + 1]
            i += 2
            continue
        if a.startswith("--browser="):
            package = a.split("=", 1)[1]
        elif a in ("--private", "--incognito"):
            forced = True
        elif a == "--public":
            forced = False
        else:
            words.append(a)
        i += 1

    if forced is not None:
        saved = cfg.get("private")
        cfg["private"] = forced
    try:
        return run(cmd, words, cfg, package)
    finally:
        if forced is not None:
            cfg["private"] = saved


def run(cmd, rest, cfg, package):
    if cmd in ("-h", "--help", "help"):
        print(__doc__.strip())
        return 0
    if cmd == "print":
        print(resolve(rest, cfg, package)[0])
        return 0
    if cmd == "explain":
        print(resolve(rest, cfg, package)[1])
        return 0
    if cmd == "kind":
        link, _ = normalize(" ".join(rest), bool(cfg.get("bare_links")))
        if link:
            print("url")
        elif bookmark_from(cfg, " ".join(rest)):
            print("bookmark")
        else:
            print("search")
        return 0
    if cmd == "engines":
        for i, name, url in ENGINES:
            print("%-12s %s" % (i, name))
        return 0
    if cmd == "defaults":
        print(json.dumps({
            "config": DEFAULTS,
            "engines": [{"id": i, "name": n, "url": u} for i, n, u in ENGINES],
        }, indent=2))
        return 0
    if cmd in ("browsers", "browser"):
        if not rest:
            print(json.dumps(browser_list(cfg), indent=2))
            return 0
        sub = rest[0]
        if sub in ("set", "remove") and len(rest) >= 2:
            package = rest[1]
            if sub == "remove":
                (cfg.get("browsers") or {}).pop(package, None)
                if cfg.get("browser") == package:
                    cfg["browser"] = "system"
                save(cfg)
                return 0
            if len(rest) < 4:
                return usage()
            key, value = rest[2], " ".join(rest[3:]).strip()
            if key not in ("name", "open", "private"):
                print("websearch: no browser setting %r" % key, file=sys.stderr)
                return 2
            entry = dict(cfg["browsers"].get(package) or {})
            entry[key] = value
            cfg["browsers"][package] = entry
            save(cfg)
            return 0
        return usage()
    if cmd in ("bookmarks", "bookmark"):
        if cmd == "bookmarks":
            if rest and rest[0] == "json":
                print(json.dumps(cfg.get("bookmarks") or [], indent=2))
            else:
                for b in cfg.get("bookmarks") or []:
                    print("%s\t%s" % (b.get("name") or "", b.get("url") or ""))
            return 0
        sub, args = (rest[0], rest[1:]) if rest else ("list", [])
        if sub in ("list", ""):
            for b in cfg.get("bookmarks") or []:
                print("%s\t%s" % (b.get("name") or "", b.get("url") or ""))
            return 0
        if sub == "add":
            if not args:
                return usage()
            url, name = args[0], " ".join(args[1:])
            got = add_bookmark(cfg, url, name)
            if not got:
                return 2
            save(cfg)
            print(got)
            return 0
        if sub == "remove":
            if not args:
                return usage()
            want = " ".join(args).strip().lower()
            before = len(cfg.get("bookmarks") or [])
            cfg["bookmarks"] = [
                b for b in cfg.get("bookmarks") or []
                if str(b.get("name") or "").strip().lower() != want
                and str(b.get("url") or "").strip().lower() != want
            ]
            save(cfg)
            print("%d removed" % (before - len(cfg["bookmarks"])))
            return 0
        if sub == "import":
            if not args:
                return usage()
            paths = args
            if args[0] == "--pick":
                try:
                    out = subprocess.run(["omalauncher", "pick"], capture_output=True, text=True, timeout=600)
                except (OSError, subprocess.SubprocessError) as e:
                    print("websearch: omalauncher pick: %s" % e, file=sys.stderr)
                    return 1
                paths = [p for p in out.stdout.splitlines() if p.strip()]
                if not paths:
                    print("websearch: nothing picked", file=sys.stderr)
                    return 1
            added = import_bookmarks(cfg, paths)
            print("imported %d bookmark%s" % (added, "" if added == 1 else "s"))
            notify("bookmarks", "imported %d" % added)
            return 0
        return usage()
    if cmd == "config":
        if not rest:
            print(json.dumps(cfg, indent=2))
            return 0
        sub, args = rest[0], rest[1:]
        if sub == "path":
            print(CONFIG)
            return 0
        if sub == "get":
            if not args:
                return usage()
            print(json.dumps(cfg.get(args[0], DEFAULTS.get(args[0]))))
            return 0
        if sub == "set":
            if len(args) < 2:
                return usage()
            key, value = args[0], " ".join(args[1:])
            if key not in DEFAULTS:
                print("websearch: no setting %r" % key, file=sys.stderr)
                return 2
            if isinstance(DEFAULTS[key], bool):
                cfg[key] = value.strip().lower() in ("1", "true", "yes", "on")
            elif key == "browser":
                found = find_browser(cfg, value)
                cfg[key] = found if found else value.strip()
            else:
                cfg[key] = value
            save(cfg)
            return 0
        if sub == "reset":
            save(dict(DEFAULTS))
            return 0
        return usage()
    if cmd == "open":
        return open_target(resolve(rest, cfg, package)[0])
    # Anything else is the text itself.
    return open_target(resolve([cmd] + rest, cfg, package)[0])


def notify(title, text):
    """A notification on the phone, for something that took a while; quiet if it can't."""
    try:
        subprocess.run(["omalauncher", "notify", title, text],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
    except (OSError, subprocess.SubprocessError):
        pass


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
