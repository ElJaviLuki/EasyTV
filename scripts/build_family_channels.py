#!/usr/bin/env python3
"""Partition channels.json into family-oriented buckets (txt list + optional JSON).

Excludes non-Spanish regional packages (Latam, Caribbean, BR, Pluto Latin, …).
The only non-Spanish channels that survive are those classified as internacionales.

Usage:
  python scripts/build_family_channels.py
  python scripts/build_family_channels.py --txt secrets/family_channels.txt --no-json
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_IN = ROOT / "secrets" / "channels.json"
DEFAULT_OUT_JSON = ROOT / "secrets" / "family_channels.json"
DEFAULT_OUT_TXT = ROOT / "secrets" / "family_channels.txt"
DEFAULT_OUT_CLEAN = ROOT / "secrets" / "channels_clean.json"

# ---------------------------------------------------------------------------
# Exclusion: foreign regionals (not “international” news / world feeds)
# ---------------------------------------------------------------------------

_FOREIGN_NAME = re.compile(
    r"""(?ix)^(?:
        \(MX\)|\(AR\)|\(CL\)|\(PE\)|\(CO\)|\(EC\)|\(VE\)|\(UY\)|\(PY\)|\(BO\)|
        \(BR\)|\(CR\)|\(GT\)|\(HN\)|\(NI\)|\(PA\)|\(DO\)|\(PR\)|\(SV\)|
        \(PLUTO\s+Latin\)|
        MX\s|BR\s|UY\s|AR\s|CL\s|PE\s|CO\s|Carib\s
    )"""
)
_FOREIGN_ANYWHERE = re.compile(
    r"""(?ix)
        \(MX\)|\(AR\)|\(CL\)|\(PE\)|\(CO\)|\(EC\)|\(VE\)|\(UY\)|\(PY\)|\(BO\)|
        \(BR\)|\(CR\)|\(GT\)|\(HN\)|\(NI\)|\(PA\)|\(DO\)|\(PR\)|
        \bCarib\b|\bPLUTO\s+Latin\b
    """
)
_FOREIGN_GROUP = re.compile(
    r"""(?ix)
        \bLA-|\bLATAM\b|\bLatin\b|
        Mexico|Argentina|Chile|Brazil|Brasil|Colombia|Venezuela|
        Uruguay|Paraguay|Bolivia|Caribbean|Peru|Ecuador|
        \bPLUTO\s+Latin\b
    """
)
_ES_GROUP = re.compile(
    r"(?i)EU-?\s*ES|\bESPANA\b|\bESPAÑA\b|\bSpain\b|\bTDT\b|EU-?\s*ES\s+REGIONAL"
)

_ADULT = re.compile(
    r"(?i)\b(?:xxx|adultos?|er[oó]tic|playboy|brazzers|venus\s*media|dorcel|"
    r"hustler|redlight|porn|babestation|hot\s*xxx)\b"
)

# Pluto / event / one-shot noise — out of thematic buckets and resto.
_FILLER_NOISE = re.compile(
    r"""(?ix)
        (?:^\s*\(PLUTO\b)
        |(?:^\s*replay\s+)
        |(?:^\s*ppv\b)
        |(?:^\s*test\s+\d)
        |(?:^\s*ita\s*\|)
        |(?:^\s*vivir\s+con\s+)
        |\bNBA\b
        |\bMLB\b
        |\(24/7\)
        |(?<!/)24/7\b
        |\beventos?\s*\d+\b
        |\bevent\s*\d+\b
        |footballclub\s+event
        |1[ªa]\s*federaci[oó]n
        |combate\s+eventos
        |max\s+eventos
        |mls\s*\d+
        |movistar\s+alquiler\b
        |c[aá]mara\s+.+\(mgptv\)
        |premier\s+league\s+replay
        |canal\s+pruebas
        |solo\s*eventos
        |dazn\s+eventos?
        |laliga\+\s*\d+\s*event
        |onboard\s+.+\(f1tv\)
        |live\s+audio\s+.+\(f1tv\)
        |tracker\s*\(f1tv\)
        |ufc\s+evento
        |dazn\s+nfl\s*\d+
        |\d{2}/\d{2}
        |vs\s+\w+\s*$
    """
)

# ---------------------------------------------------------------------------
# Imprescindibles
# ---------------------------------------------------------------------------

# Order = zap priority among generalistas.
_GENERALISTAS_ORDER = [
    "LA 1",
    "LA 2",
    "Antena 3",
    "Cuatro",
    "Telecinco",
    "laSexta",
    "24 Horas",
    "MEGA",
    "Ten",
    "Neox",
    "Nova",
    "FDF",
    "Energy",
    "Divinity",
    "BE MAD",
    "Atreseries",
    "A3SERIES",
    "BOM",
    "BOM CINE",
]
_GENERALISTAS = {n.casefold(): i for i, n in enumerate(_GENERALISTAS_ORDER)}

_EXTREMADURA = re.compile(
    r"(?i)^\s*(?:canal\s*)?extremadura(?:\s*tv)?\s*$|^\s*extremadura\s*tv\s*$"
)
_CANAL_SUR = re.compile(
    r"(?i)^\s*canal\s*sur(?:\s*2)?\s*$|^\s*andaluc[ií]a\s*tv\s*$|"
    r"^\s*7tv\s*andalucia\s*$"
)
# Solo televisiones autonómicas (no provinciales / locales).
_REGIONAL_ES = re.compile(
    r"""(?ix)^\s*(?:
        telemadrid|
        arag[oó]n\s*tv(?:\s*int(?:ernacional)?)?|
        tv3(?:cat)?|teve\.cat|
        etb(?:\s*[1-4]|\s*basque)?|
        tvg(?:\s*[12])?|galicia\s*tv(?:\s*[12])?|
        7rm|a\s*punt|ib3(?:\s*global)?|
        tv\s*canarias?|telecanarias|
        cyl(?:\s*[78])?|
        castilla(?:\s*y\s*le[oó]n)?(?:\s*tv)?|
        la\s*rioja(?:\s*tv)?|navarra(?:\s*tv)?|
        canal\s*extremadura|extremadura\s*tv|
        canal\s*sur(?:\s*2)?|andaluc[ií]a\s*tv|7tv\s*andalucia|
        esport\s*3(?:\s*catalunia)?
    )\s*$"""
)

# ---------------------------------------------------------------------------
# Persona / theme matchers
# ---------------------------------------------------------------------------

_CAZA_PESCA = re.compile(
    r"(?i)\b(?:caza\s*y\s*pesca|cazavision|iberalia(?:\s*(?:tv|caza|pesca))?|"
    r"horse\s*tv)\b"
)
_ANIMALES = re.compile(
    r"(?i)^\s*(?:nat\s*geo(?:\s*wild)?|national\s*geographic|discovery(?:\s*channel)?|"
    r"canal\s*odisea|odisea|dmax|historia|animal\s*planet|fauna|"
    r"el\s*toro(?:\s*tv)?|onetoro|torole)\s*$"
)

# Deportes: classify into ONE subsection (first match wins).
_SPORT_BALONCESTO = re.compile(
    r"(?i)\b(?:nba|acb|baloncesto|basket(?:ball)?|euroliga|euroleague)\b"
)
_SPORT_TENIS = re.compile(
    r"(?i)\b(?:tenis|tennis|\batp\b|\bwta\b)\b"
)
_SPORT_GOLF = re.compile(
    r"(?i)\b(?:golf)\b"
)
_SPORT_MOTOR = re.compile(
    r"(?i)\b(?:f1(?:tv)?|formula\s*1|motogp|moto\s*gp|moto\s*adv|rally|wrc|"
    r"motor\s*vision|motorvision|motor\s*trend|motortrend|dbike|motocicl)"
)
_SPORT_FUTBOL = re.compile(
    r"""(?ix)
        (?:
            \blaligatv\b|\blaliga\b|liga\s*de\s*campeones|gol\s*play|gol\s*tv|
            real\s*madrid(?:\s*tv)?|betis(?:\s*tv|\s*live)?|bar[cç]a\s*tv|barsa\s*tv|
            f[uú]tbol|hypermotion|champions|uefa|copa\s*del\s*rey|
            vix\+?\s*laliga|sky\s*sport\s*la\s*liga|
            bein\s*sports?\s*liga|dazn\s*.*\bliga\b|bundesliga|serie\s*a|
            fifa\+|movistar\s*l\s*campeones|movistar\s*l\s*hypermotion|
            movistar\s*la\s*liga|primera\s*federaci[oó]n
        )
    """
)
# Broad sports catch-all → general_misc (and leftover after specific sports).
_SPORT_ANY = re.compile(
    r"""(?ix)
        (?:
            \bdeporte|\bdeportes|\bsport|\bsports|eurosport|\bdazn\b|bein\s*sports?|
            movistar\s*deportes|\btdp\b|teledeporte|\bespn\b|
            \#\s*vamos|\bvamos\b|
            multideporte|esport\s*3|\bufc\b|surf\s*channel|padel|p[aá]del|
            boxeo|boxing|ciclismo|atletismo|hockey|rugby|voleibol|
            ol[ií]mpic|olympic|red\s*bull\s*tv|fight\s*time|motoamerica|
            nautica|nautical
        )
    """
)

_SPORT_SECTIONS = (
    ("baloncesto", _SPORT_BALONCESTO),
    ("tenis", _SPORT_TENIS),
    ("golf", _SPORT_GOLF),
    ("motor", _SPORT_MOTOR),
    ("futbol", _SPORT_FUTBOL),
)

# Miscelánea leftovers → exclusive subsections (first match wins).
_MISC_PROVINCIALES = re.compile(
    r"""(?ix)
        (?:
            \b7tv\b|canal\s*m[aá]laga|estepona|alcarria|algeciras|
            vega\s*fibra|vegafibra|huelva\s*tv|sevilla\s*tv|ptv\s*sevilla|
            lebrija|levante\s*tv|tele\s*(?:monovar|sax|agost|jumilla|nerja|onuba)|
            teleelx|\btele7\b|elche7?tv|aspe\s*tv|cmm\s*tv|tv\s*melilla|
            la\s*8\s*mediterraneo|la\s*otra|\bbatis\s*tv\b|canal\s*costa|
            sal\s*tv|m[ií]rame\s*tv|medinfor|cable\s*union|sfc\s*tv|
            mundo\s*cofrade
        )
    """
)
_MISC_MUSICA = re.compile(
    r"""(?ix)
        (?:
            \bmtv\b|mezzo|m[uú]sica|music\s*choice|hit\s*tv|sol\s*m[uú]sica|
            molahits|telehit|quiero\s*musica|canal\s*de\s*la\s*musica|
            classica|\bvh1\b|\btr3s\b|\bubeat\b|all\s*flamenco|movistar\s*hits|
            m\+\s*musicales
        )
    """
)
_MISC_CINE_SERIES = re.compile(
    r"""(?ix)
        (?:
            paramount|\btnt\b|tr3ce|trece(?:\s*tv)?|rakuten\w*|sonyone|\bblaze\b|
            vin\s*tv|thriller|popup|originales|resistencia|portada|
            movistar\s*\+|movistar\s*plus|m\+\s*thriller|
            pel[ií]culas?|\bpelis\b|\bmovie\b|\bfilm\b|series|drama|comedia|
            acci[oó]n|horror|rom[aá]nticas?|grjngo|cines\s*verdi|
            good\s*vercine|bbc\s*drama|trailers
        )
    """
)
_MISC_ENTRETENIMIENTO = re.compile(
    r"""(?ix)
        (?:
            dkiss|el\s*toro|gran\s*hermano|en\s*familia|cadena\s*elite|
            peluche|onetoro|torole|sin\s*filtros|tendencias|mars\s*24|
            \bnetwork\b|\bftv\b|escapatv|el\s*garage|garage\s*tv|
            movistar\s*ellas|velevisa|telenovelda
        )
    """
)
_MISC_DOCUMENTALES = re.compile(
    r"""(?ix)
        \b(?:
            documentales|bbc\s*(?:history|top\s*gear)|buen\s*viaje|
            global\s*tendencias
        )\b
    """
)
_MISC_RELIGION = re.compile(
    r"(?i)\b(?:ewtn|canal\s*luz)\b"
)
_MISC_NOTICIAS = re.compile(
    r"(?i)(?:parlamento|el\s*pa[ií]s|24h\s*inform|libertaddigital|3/24|cableworld)"
)
_MISC_INTERNACIONAL = re.compile(
    r"""(?ix)
        \b(?:
            das\s*erste|sat\.?1|zdf|caracol|arirang|al\s*aoula|1\+1|
            pro\s*tv|tv\s*polonia|tv\s*rumania|tv\s*record|awe\s*plus|
            canal\s*de\s*las\s*estrellas|viva\s*rtv
        )\b
    """
)

_MISC_SECTIONS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("provinciales", _MISC_PROVINCIALES),
    ("musica", _MISC_MUSICA),
    ("cine_series", _MISC_CINE_SERIES),
    ("entretenimiento", _MISC_ENTRETENIMIENTO),
    ("documentales", _MISC_DOCUMENTALES),
    ("religion", _MISC_RELIGION),
    ("noticias", _MISC_NOTICIAS),
    ("internacional_extra", _MISC_INTERNACIONAL),
)

_DECORACION = re.compile(
    r"(?i)^\s*(?:decasa|canal\s*decasa|amc\s*living)\s*$"
)
_COCINA = re.compile(
    r"(?i)^\s*(?:canal\s*cocina|cocina|bbc\s*food|food\s*network)\s*$"
)
_TELENOVELAS = re.compile(
    r"(?i)^\s*(?:telenovelas?|tlnovelas|nova|divinity|cosmo|fox\s*life|"
    r"pasiones|novel[ií]sima|global\s*telenovelas)\s*$"
)

_PELICULAS_NAME = re.compile(
    r"""(?ix)^\s*(?:
        movistar\s+(?:estrenos|acci[oó]n|cine(?:\s+espa[nñ]ol)?|cl[aá]sicos|
            comedia|drama|indie|suspense)|
        bom\s*cine|axn\s*movies|tcm(?:\s*moderno)?|
        hollywood|somos|dark|sundance(?:\s*tv)?|
        film&co|film\s*&?\s*co|
        runtime(?:\s+(?:acci[oó]n|cl[aá]sicos|thriller(?:\s+y\s+terror)?|cine(?:\s+y\s+series)?))?|
        amc(?:\s+(?:break|crime|western))?|
        rakuten(?:tv)?\s*cine\b.*|
        xtrm|syfy
    )\s*$"""
)

_SERIES_NAME = re.compile(
    r"""(?ix)^\s*(?:
        movistar\s+series|atreseries|a3series|
        axn(?:\s+white)?|warner\s*bros\.?\s*tv|comedy\s*central|
        star\s*channel|fox(?:\s*life)?|calle\s*13|cosmo|
        amc(?:\s+(?:break|crime|anime|western|living))?|selekt|
        sky\s*showtime(?:\s*\d+)?|
        runtime(?:\s+\S+)?|
        energy|fdf|neox|xtrm|syfy|
        series\s*m\+|bbc\s*series|
        sony(?:one)?\s*series
    ).*$"""
)

_INFANTILES = re.compile(
    r"""(?ix)^\s*(?:
        clan|boing|nickelodeon|nick\s*(?:junior|jr)|baby\s*tv|
        disney(?:\s*(?:junior|jr|ch(?:annel)?))?|
        dreamworks|pocoy[oó]|cartoon\s*network|boomerang|
        squirrel(?:\s*2)?|lolly\s*kids|super\s*3/?33|sx3|
        gametoon|panda|gulli|cbeebies
    )\s*$"""
)

_INTERNACIONALES = re.compile(
    r"""(?ix)^\s*(?:
        cnn(?:\s*inter(?:nacional)?)?|
        bbc\s*(?:news|world)?|
        al\s*jazeera(?:\s*arabic)?|
        euronews|
        dw(?:\s*en\s*espa[nñ]ol)?|deutsche\s*w(?:elle)?|
        cgtn(?:\s*espa[nñ]ol)?|
        trt(?:\s*(?:world|arabi))?|
        tv5\s*monde|
        france\s*24|
        rai(?:\s*1|\s*italia)?|
        rtp\s*internacional|
        sic\s*internacional|
        arte|
        bloomberg|
        cnbc|
        newsmax(?:\s*tv)?|
        one\s*america\s*news(?:\s*plus)?(?:\s*\(oan\))?|
        sky\s*news(?:\s*intl)?|
        nhk(?:\s*world)?|
        telesur
    )\s*$"""
)


def slim(canal: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": canal["id"],
        "number": canal.get("number"),
        "visible_name": canal["visible_name"],
        "logo": canal.get("logo"),
    }


def is_foreign_regional(canal: dict[str, Any]) -> bool:
    vn = canal["visible_name"]
    if _FOREIGN_NAME.match(vn) or _FOREIGN_ANYWHERE.search(vn):
        return True
    gs = " ".join(canal.get("group") or [])
    if _FOREIGN_GROUP.search(gs) and not _ES_GROUP.search(gs):
        return True
    return False


def is_adult(canal: dict[str, Any]) -> bool:
    return bool(_ADULT.search(canal["visible_name"]))


def is_filler(canal: dict[str, Any]) -> bool:
    return bool(_FILLER_NOISE.search(canal["visible_name"]))


def cf(name: str) -> str:
    return name.casefold()


def pick_matches(
    canales: list[dict[str, Any]],
    pred: Callable[[dict[str, Any]], bool],
) -> list[dict[str, Any]]:
    return [slim(c) for c in canales if pred(c) and not is_filler(c)]


def count_tree(obj: Any) -> int:
    if isinstance(obj, list):
        return len(obj)
    if isinstance(obj, dict):
        return sum(count_tree(v) for v in obj.values() if not isinstance(v, str))
    return 0


def classify_sport(visible_name: str) -> str | None:
    """Return sports subsection key, or None if not sports."""
    for key, pat in _SPORT_SECTIONS:
        if pat.search(visible_name):
            return key
    if _SPORT_ANY.search(visible_name):
        return "general_misc"
    return None


def natural_key(name: str) -> tuple:
    m = re.search(r"(\d+)\s*$", name)
    n = int(m.group(1)) if m else -1
    return (cf(re.sub(r"\s*\d+\s*$", "", name)), n, cf(name))


def build_deportes(thematic: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    buckets: dict[str, list[dict[str, Any]]] = {
        "general_misc": [],
        "futbol": [],
        "baloncesto": [],
        "tenis": [],
        "golf": [],
        "motor": [],
    }
    for c in thematic:
        section = classify_sport(c["visible_name"])
        if section is None:
            continue
        buckets[section].append(slim(c))

    # Primary emission first; Movistar-pack / overflow feeds last (fallbacks).
    def futbol_rank(x: dict[str, Any]) -> tuple:
        n = x["visible_name"]
        # Overflow / party feeds / DAZN bar / backup audio — almost never “main” TV.
        if re.search(
            r"(?i)(?:\bF\s*\d+\s*$|liga\s*f\s*\d+|multiaudio\s*backup|dazn\s+\d+\s*bar\b)",
            n,
        ):
            return (90, natural_key(n))
        # Movistar L* pack = same product, alternate naming / failover.
        if re.match(r"(?i)movistar\s+l(?:a)?\s+", n):
            return (80, natural_key(n))
        # Core Spanish linear (most emission).
        if re.match(r"(?i)laligatv(?:\s+\d+)?$", n):
            return (0, natural_key(n))
        if re.match(r"(?i)liga\s*de\s*campeones", n):
            return (1, natural_key(n))
        if re.match(r"(?i)laligatv\s+hypermotion", n):
            return (2, natural_key(n))
        if re.match(r"(?i)gol\s*play", n):
            return (3, natural_key(n))
        if re.match(r"(?i)(?:real\s*madrid|betis)", n):
            return (4, natural_key(n))
        if re.search(
            r"(?i)(?:laliga\+|laliga\s*tv\s*bar|vix|sky\s*sport\s*la\s*liga|"
            r"bein\s*sports?\s*liga|dazn\s+\d+\s*liga|fifa|primera\s*feder)",
            n,
        ):
            return (5, natural_key(n))
        # Other linear leagues (Bundesliga, Serie A, …).
        return (6, natural_key(n))

    buckets["futbol"].sort(key=futbol_rank)

    def general_rank(x: dict[str, Any]) -> tuple:
        n = x["visible_name"]
        # Alt packs / bar / low-use niche last.
        if re.search(r"(?i)(?:dazn\s+\d+\s*bar\b|\bit\s+dazn\b)", n):
            return (80, natural_key(n))
        if re.search(
            r"(?i)(?:fight\s*time|ufc|red\s*bull|surf|nautic|motoamerica)",
            n,
        ):
            return (90, natural_key(n))
        # Core multi-sport emission.
        if re.match(r"(?i)teledeporte$", n):
            return (0, natural_key(n))
        if re.match(r"(?i)eurosport", n):
            return (1, natural_key(n))
        if re.match(r"(?i)movistar\s+deportes", n):
            return (2, natural_key(n))
        if re.match(r"(?i)dazn\s+\d+$", n):
            return (3, natural_key(n))
        if re.match(r"(?i)vamos\s*\d*$", n):
            return (4, natural_key(n))
        if re.match(r"(?i)bein\s*sports?", n):
            return (5, natural_key(n))
        if re.match(r"(?i)(?:multideporte|esport\s*3)", n):
            return (6, natural_key(n))
        return (7, natural_key(n))

    buckets["general_misc"].sort(key=general_rank)
    for key in ("baloncesto", "tenis", "golf", "motor"):
        buckets[key].sort(key=lambda x: natural_key(x["visible_name"]))
    return buckets


def classify_misc(visible_name: str) -> str:
    for key, pat in _MISC_SECTIONS:
        if pat.search(visible_name):
            return key
    return "otros"


def build_miscelanea(items: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    buckets: dict[str, list[dict[str, Any]]] = {
        "provinciales": [],
        "musica": [],
        "cine_series": [],
        "entretenimiento": [],
        "documentales": [],
        "religion": [],
        "noticias": [],
        "internacional_extra": [],
        "otros": [],
    }
    for item in items:
        buckets[classify_misc(item["visible_name"])].append(item)
    for key in buckets:
        buckets[key].sort(key=lambda x: cf(x["visible_name"]))
    return buckets


def build(canales: list[dict[str, Any]]) -> dict[str, Any]:
    excluded_foreign: list[str] = []
    excluded_adult: list[str] = []
    kept: list[dict[str, Any]] = []

    for c in canales:
        if is_adult(c):
            excluded_adult.append(c["visible_name"])
            continue
        if is_foreign_regional(c):
            excluded_foreign.append(c["visible_name"])
            continue
        kept.append(c)

    thematic = [c for c in kept if not is_filler(c)]

    # --- imprescindibles ---
    generalistas = [
        slim(c)
        for c in sorted(
            (c for c in thematic if cf(c["visible_name"]) in _GENERALISTAS),
            key=lambda c: _GENERALISTAS[cf(c["visible_name"])],
        )
    ]

    extremadura = [
        slim(c) for c in thematic if _EXTREMADURA.search(c["visible_name"])
    ]
    canal_sur = [
        slim(c) for c in thematic if _CANAL_SUR.search(c["visible_name"])
    ]
    otros_regionales = [
        slim(c)
        for c in thematic
        if _REGIONAL_ES.search(c["visible_name"])
        and not _EXTREMADURA.search(c["visible_name"])
        and not _CANAL_SUR.search(c["visible_name"])
        and cf(c["visible_name"]) not in _GENERALISTAS
    ]
    otros_regionales.sort(key=lambda x: cf(x["visible_name"]))
    regionales = extremadura + canal_sur + otros_regionales

    # --- personas (overlap allowed across sections) ---
    deportes = build_deportes(thematic)
    abuelo = {
        "caza_pesca": pick_matches(thematic, lambda c: bool(_CAZA_PESCA.search(c["visible_name"]))),
        "animales": pick_matches(thematic, lambda c: bool(_ANIMALES.search(c["visible_name"]))),
        "deportes": deportes,
    }

    abuela = {
        "decoracion": pick_matches(thematic, lambda c: bool(_DECORACION.search(c["visible_name"]))),
        "cocina": pick_matches(thematic, lambda c: bool(_COCINA.search(c["visible_name"]))),
        "telenovelas": pick_matches(thematic, lambda c: bool(_TELENOVELAS.search(c["visible_name"]))),
    }

    tia = {
        "peliculas": pick_matches(thematic, lambda c: bool(_PELICULAS_NAME.search(c["visible_name"]))),
        "series": pick_matches(thematic, lambda c: bool(_SERIES_NAME.search(c["visible_name"]))),
    }

    primo = {
        "infantiles": pick_matches(thematic, lambda c: bool(_INFANTILES.search(c["visible_name"]))),
    }

    internacionales = pick_matches(
        thematic, lambda c: bool(_INTERNACIONALES.search(c["visible_name"]))
    )
    internacionales.sort(key=lambda x: cf(x["visible_name"]))

    claimed: set[str] = set()
    for bucket in (
        generalistas,
        regionales,
        abuelo["caza_pesca"],
        abuelo["animales"],
        *deportes.values(),
        abuela["decoracion"],
        abuela["cocina"],
        abuela["telenovelas"],
        tia["peliculas"],
        tia["series"],
        primo["infantiles"],
        internacionales,
    ):
        for item in bucket:
            claimed.add(item["id"])

    resto = [slim(c) for c in thematic if c["id"] not in claimed]
    miscelanea = build_miscelanea(resto)
    filler_count = sum(1 for c in kept if is_filler(c))

    out = {
        "grouping": "family_buckets",
        "note": (
            "Imprescindibles = generalistas ES + televisiones autonómicas "
            "(Extremadura y Canal Sur primero; sin provinciales/locales). "
            "Excluidos: regionales no españoles (Latam/Caribe/BR/…), adultos, "
            "Pluto/eventos/24-7 filler. "
            "Internacionales = feeds mundiales/noticias extranjeras permitidos. "
            "Abuelo.deportes se parte en general_misc / futbol / baloncesto / "
            "tenis / golf / motor (un canal, una subsección). "
            "Miscelánea = provinciales / música / cine_series / … / otros. "
            "channel_id: primer canal de cada gran grupo en múltiplo de 50 "
            "(el primero en 1); correlativos dentro del gran grupo. "
            "Un canal puede aparecer en varias secciones temáticas de persona."
        ),
        "counts": {
            "input": len(canales),
            "kept": len(kept),
            "excluded_foreign_regional": len(excluded_foreign),
            "excluded_adult": len(excluded_adult),
            "excluded_filler": filler_count,
            "imprescindibles_generalistas": len(generalistas),
            "imprescindibles_regionales": len(regionales),
            "abuelo": count_tree(abuelo),
            "abuelo_deportes": {k: len(v) for k, v in deportes.items()},
            "abuela": count_tree(abuela),
            "tia": count_tree(tia),
            "primo": count_tree(primo),
            "internacionales": len(internacionales),
            "miscelanea": {k: len(v) for k, v in miscelanea.items()},
            "resto": sum(len(v) for v in miscelanea.values()),
        },
        "imprescindibles": {
            "generalistas": generalistas,
            "regionales": {
                "extremadura": extremadura,
                "canal_sur_andalucia": canal_sur,
                "otros": otros_regionales,
            },
        },
        "abuelo": {
            "perfil": "ESTJ 3w2",
            "temas": "caza y pesca, animales, deportes (general/misc, fútbol, baloncesto, tenis, golf, motor)",
            **abuelo,
        },
        "abuela": {
            "perfil": "ISFP",
            "temas": "decoración, cocina, telenovelas",
            **abuela,
        },
        "tia": {
            "perfil": "ENFJ",
            "temas": "películas, series",
            **tia,
        },
        "primo": {
            "perfil": "INFP",
            "temas": "infantiles",
            **primo,
        },
        "internacionales": internacionales,
        "miscelanea": miscelanea,
    }
    assign_channel_ids(out)
    return out


def ceil_multiple(n: int, step: int = 50) -> int:
    """Smallest multiple of `step` that is >= n."""
    if n <= 0:
        return step
    if n % step == 0:
        return n
    return ((n + step - 1) // step) * step


def iter_great_groups(data: dict[str, Any]) -> list[list[list[dict[str, Any]]]]:
    """Top-level family groups → ordered subgroups → channel lists.

    Numbering is continuous across subgroups of the same great group;
    each new great group starts at the next multiple of 50 (first group at 1).
    """
    imp = data["imprescindibles"]
    reg = imp["regionales"]
    abuelo = data["abuelo"]
    dep = abuelo["deportes"]
    abuela = data["abuela"]
    tia = data["tia"]
    misc = data["miscelanea"]
    return [
        [  # Imprescindibles
            imp["generalistas"],
            reg["extremadura"],
            reg["canal_sur_andalucia"],
            reg["otros"],
        ],
        [  # Abuelo
            abuelo["caza_pesca"],
            abuelo["animales"],
            dep["general_misc"],
            dep["futbol"],
            dep["baloncesto"],
            dep["tenis"],
            dep["golf"],
            dep["motor"],
        ],
        [  # Abuela
            abuela["decoracion"],
            abuela["cocina"],
            abuela["telenovelas"],
        ],
        [  # Tía
            tia["peliculas"],
            tia["series"],
        ],
        [data["primo"]["infantiles"]],
        [data["internacionales"]],
        [  # Miscelánea
            misc["provinciales"],
            misc["musica"],
            misc["cine_series"],
            misc["entretenimiento"],
            misc["documentales"],
            misc["religion"],
            misc["noticias"],
            misc["internacional_extra"],
            misc["otros"],
        ],
    ]


def assign_channel_ids(data: dict[str, Any]) -> None:
    """Mutate slim canal dicts with family channel_id (zap-style)."""
    next_id = 1
    first_great = True
    for great in iter_great_groups(data):
        flat = [c for sub in great for c in sub]
        if not flat:
            continue
        if first_great:
            cur = 1
            first_great = False
        else:
            cur = ceil_multiple(next_id, 50)
        for sub in great:
            for canal in sub:
                canal["channel_id"] = cur
                cur += 1
        next_id = cur


def _names(items: list[dict[str, Any]]) -> list[str]:
    return [x["visible_name"] for x in items]


def _numbered_lines(items: list[dict[str, Any]]) -> list[str]:
    return [f"{x['channel_id']} {x['visible_name']}" for x in items]


def render_txt(data: dict[str, Any]) -> str:
    """Channel list grouped by blank lines; no section headers."""
    lines: list[str] = []

    def section(items: list[dict[str, Any]]) -> None:
        if not items:
            return
        if lines:
            lines.append("")
        lines.extend(_numbered_lines(items))

    for great in iter_great_groups(data):
        for sub in great:
            section(sub)

    return "\n".join(lines) + ("\n" if lines else "")


def build_channels_clean(
    family: dict[str, Any],
    source_canales: list[dict[str, Any]],
) -> dict[str, Any]:
    """Full Canal records for the family zap list, ordered by channel_id.

    Dedupes by canal id keeping the first (lowest) channel_id appearance.
    Sets number = family channel_id. Drops foreign/adult/filler via family filter.
    """
    by_id = {c["id"]: c for c in source_canales if isinstance(c, dict) and c.get("id")}

    chosen: dict[str, int] = {}  # canal id → channel_id
    for great in iter_great_groups(family):
        for sub in great:
            for slim_c in sub:
                cid = slim_c["id"]
                ch_id = int(slim_c["channel_id"])
                if cid not in chosen or ch_id < chosen[cid]:
                    chosen[cid] = ch_id

    clean: list[dict[str, Any]] = []
    missing = 0
    for cid, ch_id in sorted(chosen.items(), key=lambda kv: kv[1]):
        src = by_id.get(cid)
        if not src:
            missing += 1
            continue
        canal = {
            "id": src["id"],
            "number": ch_id,
            "visible_name": src.get("visible_name") or slim_name(family, cid),
            "name": list(src.get("name") or []),
            "group": list(src.get("group") or []),
            "logo": src.get("logo"),
            "lives": list(src.get("lives") or []),
        }
        clean.append(canal)

    return {
        "grouping": "family_clean",
        "grouping_note": (
            "Canales del listado familiar EasyTV: curados, sin Latam/adultos/filler, "
            "ordenados por channel_id (gran grupo en múltiplos de 50). "
            "number = channel_id de zap familiar."
        ),
        "counts": {
            "canales": len(clean),
            "lives": sum(len(c.get("lives") or []) for c in clean),
            "missing_source": missing,
            "channel_id_min": clean[0]["number"] if clean else None,
            "channel_id_max": clean[-1]["number"] if clean else None,
        },
        "canales": clean,
    }


def slim_name(family: dict[str, Any], cid: str) -> str:
    for great in iter_great_groups(family):
        for sub in great:
            for c in sub:
                if c["id"] == cid:
                    return c["visible_name"]
    return cid


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--in", dest="inp", type=Path, default=DEFAULT_IN)
    ap.add_argument("--txt", type=Path, default=DEFAULT_OUT_TXT)
    ap.add_argument(
        "--json",
        type=Path,
        default=None,
        help=f"Also write family JSON (default: {DEFAULT_OUT_JSON.name})",
    )
    ap.add_argument(
        "--clean",
        type=Path,
        default=DEFAULT_OUT_CLEAN,
        help=f"Write cleaned channels JSON (default: {DEFAULT_OUT_CLEAN})",
    )
    ap.add_argument("--no-json", action="store_true", help="Skip family JSON output")
    ap.add_argument("--no-clean", action="store_true", help="Skip channels_clean.json")
    args = ap.parse_args()

    data = json.loads(args.inp.read_text(encoding="utf-8-sig"))
    canales = data.get("canales") or []
    out = build(canales)

    args.txt.parent.mkdir(parents=True, exist_ok=True)
    args.txt.write_text(render_txt(out), encoding="utf-8")

    json_path: Path | None = None
    if not args.no_json:
        json_path = args.json or args.txt.with_suffix(".json")
        json_path.write_text(
            json.dumps(out, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    clean_path: Path | None = None
    if not args.no_clean:
        clean = build_channels_clean(out, canales)
        clean_path = args.clean
        clean_path.write_text(
            json.dumps(clean, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    c = out["counts"]
    dep = c.get("abuelo_deportes") or {}
    dep_s = " · ".join(f"{k} {v}" for k, v in dep.items())
    misc = c.get("miscelanea") or {}
    misc_s = " · ".join(f"{k} {v}" for k, v in misc.items() if v)
    extras = []
    if json_path:
        extras.append(str(json_path))
    if clean_path:
        extras.append(str(clean_path))
    extra = (" + " + " + ".join(extras)) if extras else ""
    clean_n = ""
    if clean_path:
        clean_data = json.loads(clean_path.read_text(encoding="utf-8"))
        clean_n = f"\n  channels_clean: {clean_data['counts']['canales']} canales, ids {clean_data['counts']['channel_id_min']}–{clean_data['counts']['channel_id_max']}"
    print(
        f"OK family list -> {args.txt}{extra}\n"
        f"  kept {c['kept']}/{c['input']} "
        f"(excl. foreign {c['excluded_foreign_regional']}, "
        f"adult {c['excluded_adult']}, filler {c['excluded_filler']})\n"
        f"  gen {c['imprescindibles_generalistas']} · reg {c['imprescindibles_regionales']} · "
        f"abuelo {c['abuelo']} · abuela {c['abuela']} · tía {c['tia']} · "
        f"primo {c['primo']} · intl {c['internacionales']} · misc {c['resto']}\n"
        f"  deportes: {dep_s}\n"
        f"  misc: {misc_s}"
        f"{clean_n}"
    )


if __name__ == "__main__":
    main()
