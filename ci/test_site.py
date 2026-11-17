"""Tests for ci/site_check.py and ci/site_build.py — the static website at rail8.dev.

Run: python3 -m unittest ci.test_site  (from the repository root; also run by the CI build job and the site workflow)

The contract these tests define (the implementer writes both scripts to it):

ci/site_check.py
  problems(root) -> list[str]
      `root` is a str or a path-like naming the site directory. One string per problem; no problem -> []. Every string
      starts with "<file>: " where <file> is the path of the offending file relative to `root`, '/'-separated
      ("index.html", "docs/page.html", "brand/note.svg"); the rest says what is wrong and holds:
        rule 1  no index.html            -> the line starts with "index.html: " and holds "missing"
        rule 2  unresolved reference     -> the reference as written in the file ("img/none.png"; for srcset the
                                            one candidate URL without its descriptor)
        rule 3  load from another origin -> the URL as written ("https://cdn.example/x.js", "//cdn.example/x.png")
        rule 4  a <script> element       -> "script"
        rule 5  a missing or empty item  -> "lang", "title", "viewport", "canonical", "description" or "alt"
        rule 6  the forbidden word       -> "replay"
      HTML files are the files ending in ".html", CSS files those ending in ".css", at any depth under `root`.
      Rules 2 and 3 read HTML and CSS files only; rule 6 reads every file (bytes, any letter case).
      Rule 2: a reference is local unless it starts with "#", with "//" or with a URL scheme ("mailto:", "https:",
      ...). Its path part (before "?" or "#") resolves against the referring file's directory, or against `root`
      when it starts with "/"; it must name an existing regular file inside `root`. A path ending in "/" ("/",
      "docs/") names that directory's index.html; a directory named without the "/" is not a file.
      The CSS of an HTML file counts as CSS: a url() in an inline <style> element or in a style="" attribute that
      names another origin is a rule-3 problem of that HTML file, and a local one that names a missing file is a
      rule-2 problem of it. A url() inside a CSS comment (/* ... */) is no reference.
  python3 ci/site_check.py <dir>
      Prints problems(<dir>) to stdout, one per line, nothing else; exits 1 when there is one, 0 when there is none
      (a clean run prints nothing).

ci/site_build.py
  python3 ci/site_build.py <out-dir>     (run from the repository root; <out-dir> need not exist)
      Exits 0 and writes every file of site/ at the same relative path under <out-dir>, byte for byte, plus, under
      <out-dir>/brand/, the files of docs/brand/ that the pages refer to as "brand/<file>" (byte for byte, and no
      other), and nothing else. site/ itself holds no copy of a brand file and no CNAME file.

Each SiteCheckTest case builds a small site in a temporary directory: a clean base site, changed by one thing.
"""
import html
import importlib.util
import re
import subprocess
import sys
import tempfile
import unittest
from html.parser import HTMLParser
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CHECK = REPO / "ci" / "site_check.py"
BUILD = REPO / "ci" / "site_build.py"
SITE = REPO / "site"
BRAND = REPO / "docs" / "brand"
TIMEOUT = 120
# The owner's line (2026-11-15), as phrases of the page's visible words and of its description meta, lower case.
LINE = ("a modern open-source alternative to legacy integration middleware", "java 25 virtual threads",
        "fast by design", "every message traceable end to end")

VIEWPORT = '<meta name="viewport" content="width=device-width, initial-scale=1">'
CANONICAL = '<link rel="canonical" href="https://rail8.dev/">'
DESCRIPTION = '<meta name="description" content="An integration platform.">'
TITLE = "<title>Rail8</title>"
HTML_TAG = '<html lang="en">'


def page(body: str = "", *, head: str = "", html_tag: str = HTML_TAG, title: str = TITLE, viewport: str = VIEWPORT,
         canonical: str = CANONICAL, description: str = DESCRIPTION) -> str:
    """An HTML page that breaks no rule unless a keyword argument makes it."""
    return ("<!doctype html>\n" + html_tag + "\n<head>\n<meta charset=\"utf-8\">\n" + viewport + "\n" + title + "\n"
            + description + "\n" + canonical + "\n" + '<link rel="icon" href="brand/favicon.png">\n'
            + '<link rel="stylesheet" href="style.css">\n' + head + "</head>\n<body>\n<h1>Rail8</h1>\n"
            + '<img src="brand/logo.svg" alt="Rail8 logo">\n' + body + "\n</body>\n</html>\n")


def not_found_page(body: str = "", **kw) -> str:
    """A second HTML page: needs no canonical link and no description."""
    kw.setdefault("canonical", "")
    kw.setdefault("description", "")
    return page("<p>Not found.</p>\n" + body, **kw)


BASE_CSS = "body { font-family: system-ui, sans-serif; }\n"
LOGO_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><title>rail8</title></svg>\n'
FAVICON_PNG = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\xff\xfe\x80\x00"

_module = None


def site_check():
    """The checker under test, loaded from its file (no import cache across a changed file)."""
    global _module
    if _module is None:
        if not CHECK.is_file():
            raise AssertionError(f"{CHECK} does not exist")
        spec = importlib.util.spec_from_file_location("site_check_under_test", CHECK)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _module = module
    return _module


def run_check(root: Path):
    """Runs the checker as CI does; returns (exit code, non-empty stdout lines, stdout + stderr)."""
    if not CHECK.is_file():
        raise AssertionError(f"{CHECK} does not exist")
    done = subprocess.run([sys.executable, str(CHECK), str(root)], cwd=REPO, capture_output=True, timeout=TIMEOUT)
    out = done.stdout.decode("utf-8", errors="replace")
    err = done.stderr.decode("utf-8", errors="replace")
    return done.returncode, [line for line in out.splitlines() if line.strip()], out + err


class SiteCheckTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "site"
        self.write("index.html", page())
        self.write("404.html", not_found_page())
        self.write("style.css", BASE_CSS)
        self.write("brand/logo.svg", LOGO_SVG)
        self.write("brand/favicon.png", FAVICON_PNG)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, path: str, content) -> None:
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            p.write_bytes(content)
        else:
            p.write_text(content, encoding="utf-8")

    def problems(self) -> list:
        found = site_check().problems(self.root)
        self.assertIsInstance(found, list)
        return found

    def assertOnly(self, file: str, *words: str) -> None:
        """Exactly one problem, naming `file` first and holding every word."""
        found = self.problems()
        self.assertEqual(1, len(found), f"expected one problem in {file} holding {words}, got {found}")
        self.assertTrue(found[0].startswith(file + ": "), f"the problem does not name {file} first: {found[0]}")
        for word in words:
            self.assertIn(word, found[0])

    def assertReported(self, file: str, *words: str) -> None:
        """Some problem names `file` first and holds every word (other problems allowed)."""
        found = self.problems()
        self.assertTrue(any(p.startswith(file + ": ") and all(w in p for w in words) for p in found),
                        f"no problem in {file} holding {words}: {found}")

    def assertClean(self) -> None:
        self.assertEqual([], self.problems())

    # --- the base fixture -------------------------------------------------------------------------------------------

    def test_the_base_site_with_a_binary_file_and_an_svg_namespace_has_no_problem(self):
        self.assertClean()

    # --- rule 1: index.html exists ----------------------------------------------------------------------------------

    def test_rule1_a_site_without_index_html_is_a_problem_naming_index_html(self):
        (self.root / "index.html").unlink()
        self.assertOnly("index.html", "missing")

    def test_rule1_index_html_in_a_subdirectory_only_does_not_count(self):
        (self.root / "index.html").rename(self.root / "brand" / "index.html")
        self.assertReported("index.html", "missing")

    # --- rule 2: every local reference resolves to a file inside the site -----------------------------------------

    def test_rule2_an_href_to_a_missing_local_file_is_a_problem_naming_file_and_reference(self):
        self.write("index.html", page('<p><a href="docs/missing.html">docs</a></p>'))
        self.assertOnly("index.html", "docs/missing.html")

    def test_rule2_a_src_to_a_missing_local_file_is_a_problem_naming_file_and_reference(self):
        self.write("404.html", not_found_page('<img src="img/none.png" alt="none">'))
        self.assertOnly("404.html", "img/none.png")

    def test_rule2_a_srcset_candidate_to_a_missing_local_file_is_a_problem_naming_that_candidate(self):
        self.write("index.html", page('<img src="brand/logo.svg" srcset="brand/logo.svg 1x, brand/logo-2x.png 2x"'
                                      ' alt="logo">'))
        self.assertOnly("index.html", "brand/logo-2x.png")

    def test_rule2_a_css_url_to_a_missing_local_file_is_a_problem_naming_the_css_file_and_reference(self):
        self.write("style.css", BASE_CSS + ".hero { background: url(\"img/bg.png\"); }\n")
        self.assertOnly("style.css", "img/bg.png")

    def test_rule2_a_css_url_in_a_subdirectory_resolves_against_the_css_files_directory(self):
        self.write("css/extra.css", ".mark { background-image: url(../brand/logo.svg); }\n"
                                    ".mark2 { background-image: url('logo.svg'); }\n")
        self.write("index.html", page(head='<link rel="stylesheet" href="css/extra.css">\n'))
        self.assertOnly("css/extra.css", "logo.svg")
        self.assertNotIn("../brand/logo.svg", self.problems()[0])

    def test_rule2_a_reference_leaving_the_site_directory_is_a_problem_even_when_the_file_exists(self):
        (Path(self.tmp.name) / "outside.png").write_bytes(b"png")
        self.write("index.html", page('<img src="../outside.png" alt="outside">'))
        self.assertOnly("index.html", "../outside.png")

    def test_rule2_a_reference_to_a_directory_is_not_a_file(self):
        self.write("index.html", page('<p><a href="brand">brand</a></p>'))
        self.assertOnly("index.html", "brand")

    def test_rule2_a_path_ending_in_slash_names_the_index_html_of_that_directory(self):
        self.write("404.html", not_found_page('<p><a href="/">home</a> <a href="./">here</a></p>'))
        self.assertClean()
        self.write("404.html", not_found_page('<p><a href="brand/">brand</a></p>'))
        self.assertOnly("404.html", "brand/")

    def test_rule2_existing_href_src_srcset_and_css_url_references_resolve(self):
        self.write("docs/page.html", not_found_page('<p><a href="../index.html">home</a></p>',
                                                    head='<link rel="stylesheet" href="../style.css">\n')
                   .replace('href="brand/favicon.png"', 'href="../brand/favicon.png"')
                   .replace('src="brand/logo.svg"', 'src="../brand/logo.svg"')
                   .replace('href="style.css"', 'href="../style.css"'))
        self.write("index.html", page('<p><a href="docs/page.html">docs</a></p>\n'
                                      '<picture><source srcset="brand/logo.svg 1x, brand/favicon.png 2x">'
                                      '<img src="brand/logo.svg" srcset="brand/favicon.png 2x" alt="logo"></picture>'))
        self.write("style.css", BASE_CSS + ".a { background: url(brand/logo.svg); }\n"
                                           ".b { background: url('brand/favicon.png'); }\n"
                                           ".c { background: url( \"brand/logo.svg\" ); }\n")
        self.assertClean()

    def test_rule2_a_root_relative_reference_resolves_against_the_site_directory(self):
        self.write("404.html", not_found_page('<p><a href="/index.html">home</a></p>',
                                              head='<link rel="stylesheet" href="/style.css">\n'))
        self.assertClean()
        self.write("404.html", not_found_page('<p><a href="/nothere.html">home</a></p>'))
        self.assertOnly("404.html", "/nothere.html")

    def test_rule2_a_root_relative_reference_from_a_subdirectory_resolves_against_the_site_not_the_referrer(self):
        def sub_page(body):
            return (not_found_page(body, head='<link rel="stylesheet" href="/style.css">\n')
                    .replace('href="brand/favicon.png"', 'href="/brand/favicon.png"')
                    .replace('src="brand/logo.svg"', 'src="/brand/logo.svg"')
                    .replace('href="style.css"', 'href="/style.css"'))
        self.write("docs/page.html", sub_page('<p><a href="/index.html">home</a></p>'))
        self.assertClean()
        self.write("docs/page.html", sub_page('<p><a href="/nothere.html">home</a></p>'))
        self.assertOnly("docs/page.html", "/nothere.html")

    def test_rule2_query_and_fragment_of_a_local_reference_are_not_part_of_its_path(self):
        self.write("index.html", page('<p><a href="404.html#top">a</a> <a href="style.css?v=1">b</a></p>'))
        self.assertClean()

    def test_rule2_fragments_mailto_and_links_to_another_origin_are_not_files(self):
        self.write("index.html", page('<p id="top"><a href="#top">top</a> <a href="#">self</a>'
                                      ' <a href="mailto:team@example.org">mail</a>'
                                      ' <a href="https://github.com/example/repo">repo</a>'
                                      ' <a href="http://example.org/plain">plain</a>'
                                      ' <a href="//example.org/x">other</a></p>'))
        self.assertClean()

    # --- rule 3: nothing is loaded from another origin --------------------------------------------------------------

    def test_rule3_a_script_src_from_another_origin_is_a_problem_naming_the_url(self):
        self.write("index.html", page(head='<script src="https://cdn.example/x.js"></script>\n'))
        self.assertReported("index.html", "https://cdn.example/x.js")

    def test_rule3_a_stylesheet_link_from_another_origin_is_a_problem_naming_the_url(self):
        self.write("index.html", page(head='<link rel="stylesheet" href="https://fonts.example/css?family=X">\n'))
        self.assertOnly("index.html", "https://fonts.example/css?family=X")

    def test_rule3_an_icon_link_from_a_protocol_relative_url_is_a_problem(self):
        self.write("index.html", page(head='<link rel="icon" href="//cdn.example/favicon.ico">\n'))
        self.assertOnly("index.html", "//cdn.example/favicon.ico")

    def test_rule3_an_img_src_over_http_is_a_problem_naming_the_url(self):
        self.write("404.html", not_found_page('<img src="http://images.example/a.png" alt="a">'))
        self.assertOnly("404.html", "http://images.example/a.png")

    def test_rule3_an_img_src_from_a_protocol_relative_url_is_a_problem(self):
        self.write("index.html", page('<img src="//cdn.example/b.png" alt="b">'))
        self.assertOnly("index.html", "//cdn.example/b.png")

    def test_rule3_an_img_srcset_candidate_from_another_origin_is_a_problem(self):
        self.write("index.html", page('<img src="brand/logo.svg" srcset="https://cdn.example/c.png 2x" alt="c">'))
        self.assertOnly("index.html", "https://cdn.example/c.png")

    def test_rule3_a_source_srcset_from_another_origin_is_a_problem(self):
        self.write("index.html", page('<picture><source srcset="https://cdn.example/d.webp" type="image/webp">'
                                      '<img src="brand/logo.svg" alt="d"></picture>'))
        self.assertOnly("index.html", "https://cdn.example/d.webp")

    def test_rule3_a_source_src_from_another_origin_is_a_problem(self):
        self.write("index.html", page('<video><source src="//media.example/e.mp4" type="video/mp4"></video>'))
        self.assertOnly("index.html", "//media.example/e.mp4")

    def test_rule3_an_iframe_from_another_origin_is_a_problem(self):
        self.write("index.html", page('<iframe src="https://www.example.com/embed/f" title="f"></iframe>'))
        self.assertOnly("index.html", "https://www.example.com/embed/f")

    def test_rule3_a_css_url_from_another_origin_is_a_problem_naming_the_css_file(self):
        self.write("style.css", BASE_CSS + "@font-face { font-family: X; src: url(https://fonts.example/x.woff2); }\n")
        self.assertOnly("style.css", "https://fonts.example/x.woff2")

    def test_rule3_a_protocol_relative_css_url_is_a_problem(self):
        self.write("style.css", BASE_CSS + ".g { background: url('//cdn.example/g.png'); }\n")
        self.assertOnly("style.css", "//cdn.example/g.png")

    def test_rule3_a_css_import_of_a_string_from_another_origin_is_a_problem(self):
        self.write("style.css", '@import "https://fonts.example/h.css";\n' + BASE_CSS)
        self.assertOnly("style.css", "https://fonts.example/h.css")

    def test_rule3_a_css_import_of_a_url_from_another_origin_is_a_problem(self):
        self.write("style.css", "@import url(http://fonts.example/i.css);\n" + BASE_CSS)
        self.assertReported("style.css", "http://fonts.example/i.css")

    def test_rule3_an_inline_style_element_url_from_another_origin_is_a_problem_of_the_html_file(self):
        self.write("index.html", page(head="<style>.s { background: url(https://cdn.example/s.png); }</style>\n"))
        self.assertOnly("index.html", "https://cdn.example/s.png")

    def test_rule3_a_style_attribute_url_from_another_origin_is_a_problem_of_the_html_file(self):
        self.write("404.html", not_found_page('<p style="background: url(//cdn.example/t.png)">t</p>'))
        self.assertOnly("404.html", "//cdn.example/t.png")

    def test_rule2_a_missing_local_url_in_an_inline_style_element_is_a_problem(self):
        self.write("index.html", page(head="<style>.u { background: url(img/u.png); }</style>\n"))
        self.assertOnly("index.html", "img/u.png")

    def test_rule2_a_missing_local_url_in_a_style_attribute_is_a_problem(self):
        self.write("index.html", page("<p style=\"background-image: url('img/v.png')\">v</p>"))
        self.assertOnly("index.html", "img/v.png")

    def test_rule2_existing_local_urls_in_inline_style_resolve(self):
        self.write("index.html", page("<p style=\"background: url('brand/logo.svg')\">w</p>",
                                      head="<style>.w { background: url(brand/favicon.png); }</style>\n"))
        self.assertClean()

    def test_a_url_inside_a_css_comment_is_no_reference(self):
        self.write("style.css", "/* url(https://cdn.example/x.png) and\n   url('img/missing.png') */\n" + BASE_CSS
                   + ".k { color: #151515; } /* @import url(//cdn.example/k.css); */\n")
        self.assertClean()

    def test_rule3_the_canonical_link_and_anchors_to_another_origin_are_allowed(self):
        self.write("index.html", page('<p><a href="https://github.com/subapavel/rail8">repository</a>'
                                      ' <a href="//example.org/y">y</a></p>'))
        self.assertClean()

    # --- rule 4: no <script> element ------------------------------------------------------------------------------

    def test_rule4_an_inline_script_element_is_a_problem(self):
        self.write("404.html", not_found_page("<script>document.title = 'x';</script>"))
        self.assertOnly("404.html", "script")

    def test_rule4_a_local_script_src_is_a_problem_although_the_file_exists(self):
        self.write("app.js", "console.log(1);\n")
        self.write("index.html", page('<script src="app.js"></script>'))
        self.assertOnly("index.html", "script")

    def test_rule4_the_word_script_in_text_and_a_noscript_free_page_are_not_a_problem(self):
        self.write("index.html", page("<p>No <code>&lt;script&gt;</code> on this page; no JavaScript.</p>"))
        self.assertClean()

    # --- rule 5: lang, title, viewport; index: canonical, description; img alt --------------------------------------

    def test_rule5_an_html_element_without_lang_is_a_problem(self):
        self.write("404.html", not_found_page(html_tag="<html>"))
        self.assertOnly("404.html", "lang")

    def test_rule5_a_page_without_title_is_a_problem(self):
        self.write("404.html", not_found_page(title=""))
        self.assertOnly("404.html", "title")

    def test_rule5_a_blank_title_is_a_problem(self):
        self.write("index.html", page(title="<title>  \n </title>"))
        self.assertOnly("index.html", "title")

    def test_rule5_a_page_without_viewport_meta_is_a_problem(self):
        self.write("404.html", not_found_page(viewport='<meta name="robots" content="noindex">'))
        self.assertOnly("404.html", "viewport")

    def test_rule5_index_without_canonical_link_is_a_problem(self):
        self.write("index.html", page(canonical=""))
        self.assertOnly("index.html", "canonical")

    def test_rule5_index_with_a_canonical_link_to_another_url_is_a_problem(self):
        self.write("index.html", page(canonical='<link rel="canonical" href="https://www.rail8.dev/">'))
        self.assertOnly("index.html", "canonical")

    def test_rule5_index_without_description_meta_is_a_problem(self):
        self.write("index.html", page(description=""))
        self.assertOnly("index.html", "description")

    def test_rule5_index_with_an_empty_description_is_a_problem(self):
        self.write("index.html", page(description='<meta name="description" content=" ">'))
        self.assertOnly("index.html", "description")

    def test_rule5_an_img_without_alt_is_a_problem(self):
        self.write("404.html", not_found_page('<img src="brand/favicon.png">'))
        self.assertOnly("404.html", "alt")

    def test_rule5_an_empty_alt_counts_and_other_pages_need_no_canonical_or_description(self):
        self.write("404.html", not_found_page('<img src="brand/favicon.png" alt="">'))
        self.write("docs/more.html", not_found_page()
                   .replace('href="brand/favicon.png"', 'href="../brand/favicon.png"')
                   .replace('src="brand/logo.svg"', 'src="../brand/logo.svg"')
                   .replace('href="style.css"', 'href="../style.css"'))
        self.assertClean()

    def test_rule5_a_page_in_a_subdirectory_is_checked_too(self):
        self.write("docs/more.html", not_found_page(title="")
                   .replace('href="brand/favicon.png"', 'href="../brand/favicon.png"')
                   .replace('src="brand/logo.svg"', 'src="../brand/logo.svg"')
                   .replace('href="style.css"', 'href="../style.css"'))
        self.assertOnly("docs/more.html", "title")

    # --- rule 6: no file holds the word "replay" (any case) ---------------------------------------------------------

    def test_rule6_the_word_in_an_html_page_is_a_problem(self):
        self.write("index.html", page("<p>Messages can be replayed.</p>"))
        self.assertOnly("index.html", "replay")

    def test_rule6_the_word_in_upper_case_in_a_css_comment_is_a_problem(self):
        self.write("style.css", "/* REPLAY panel */\n" + BASE_CSS)
        self.assertOnly("style.css", "replay")

    def test_rule6_the_word_in_any_other_file_at_any_depth_is_a_problem(self):
        self.write("brand/note.svg", "<svg><desc>Replayable</desc></svg>\n")
        self.assertOnly("brand/note.svg", "replay")

    def test_rule6_relay_play_and_re_play_are_not_the_word(self):
        self.write("index.html", page("<p>It relays messages; play it again, re-play nothing.</p>"))
        self.assertClean()

    # --- the command line -------------------------------------------------------------------------------------------

    def test_the_command_line_exits_0_and_prints_nothing_on_a_clean_site(self):
        code, lines, out = run_check(self.root)
        self.assertEqual(0, code, out)
        self.assertEqual([], lines, out)

    def test_the_command_line_prints_one_line_per_problem_and_exits_1(self):
        self.write("index.html", page('<p><a href="nowhere.html">x</a></p>'))
        self.write("style.css", "/* replay */\n" + BASE_CSS)
        code, lines, out = run_check(self.root)
        self.assertEqual(1, code, out)
        self.assertNotIn("Traceback", out)
        self.assertEqual(2, len(lines), out)
        self.assertEqual(sorted(self.problems()), sorted(lines))
        self.assertTrue(any(line.startswith("index.html: ") and "nowhere.html" in line for line in lines), out)
        self.assertTrue(any(line.startswith("style.css: ") and "replay" in line for line in lines), out)

    def test_the_command_line_exits_1_on_a_missing_index(self):
        (self.root / "index.html").unlink()
        code, lines, out = run_check(self.root)
        self.assertEqual(1, code, out)
        self.assertEqual(1, len(lines), out)
        self.assertTrue(lines[0].startswith("index.html: "), out)


class _Page(HTMLParser):
    """Text, hrefs, link rels and heading levels of one HTML page."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.text, self.hrefs, self.links, self.headings, self._skip = [], [], [], [], 0
        self.metas = {}

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag in ("style", "script", "head"):
            self._skip += 1
        if a.get("href") is not None:
            self.hrefs.append(a["href"])
        if tag == "meta" and a.get("name"):
            self.metas[a["name"].lower()] = a.get("content") or ""
        if tag == "link":
            self.links.append(((a.get("rel") or "").lower().split(), a.get("href") or ""))
        if re.fullmatch(r"h[1-6]", tag):
            self.headings.append(int(tag[1]))

    def handle_endtag(self, tag):
        if tag in ("style", "script", "head") and self._skip:
            self._skip -= 1

    def handle_data(self, data):
        if not self._skip:
            self.text.append(data)

    def words(self) -> str:
        return re.sub(r"\s+", " ", " ".join(self.text).replace("\xa0", " ")).strip()


def parsed(path: Path) -> _Page:
    p = _Page()
    p.feed(path.read_text(encoding="utf-8"))
    p.close()
    return p


class RealSiteTest(unittest.TestCase):
    """Builds the real site (site/ + the brand files it uses) with ci/site_build.py and checks it."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name) / "_site"
        self.assertTrue(BUILD.is_file(), f"{BUILD} does not exist")
        self.assertTrue(SITE.is_dir(), f"{SITE} does not exist")
        done = subprocess.run([sys.executable, "ci/site_build.py", str(self.out)], cwd=REPO, capture_output=True,
                              timeout=TIMEOUT)
        self.assertEqual(0, done.returncode, (done.stdout + done.stderr).decode("utf-8", errors="replace"))

    def tearDown(self):
        self.tmp.cleanup()

    def files(self, root: Path) -> dict:
        return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(root.rglob("*")) if p.is_file()}

    def test_the_built_site_holds_index_and_brand_files_and_site_check_reports_nothing(self):
        for name in ("index.html", "404.html", "style.css"):
            self.assertTrue((self.out / name).is_file(), f"{name} is not in the built site")
        self.assertTrue(any((self.out / "brand").glob("*")), "the built site has no brand/ file")
        self.assertEqual([], site_check().problems(self.out))
        code, lines, out = run_check(self.out)
        self.assertEqual(0, code, out)
        self.assertEqual([], lines, out)

    def test_the_build_copies_site_and_only_the_brand_files_the_pages_use_byte_for_byte(self):
        built, sources, brand = self.files(self.out), self.files(SITE), self.files(BRAND)
        for name, data in sources.items():
            self.assertEqual(data, built.get(name), f"site/{name} is not copied as is")
        extra = {name for name in built if name not in sources}
        for name in extra:
            self.assertTrue(name.startswith("brand/") and name.count("/") == 1, f"unexpected built file {name}")
            self.assertEqual(brand.get(name[len("brand/"):]), built[name], f"{name} is not docs/{name} byte for byte")
        pages = " ".join(data.decode("utf-8") for name, data in built.items() if name.endswith((".html", ".css")))
        for name in extra:
            self.assertIn(name, pages, f"{name} is copied but no page refers to it")
        names = {name[len("brand/"):] for name in extra}
        for needed in ("rail8-logo.svg", "rail8-logo-dark.svg", "apple-touch-icon.png"):
            self.assertIn(needed, names)
        self.assertTrue(any(n.startswith("favicon") for n in names), f"no favicon in {sorted(names)}")
        self.assertTrue(any(n.startswith("rail8-mark") for n in names), f"no mark in {sorted(names)}")

    def test_site_holds_no_copy_of_a_brand_file_and_no_cname(self):
        brand = set(self.files(BRAND).values())
        for name, data in self.files(SITE).items():
            self.assertNotIn(data, brand, f"site/{name} is a copy of a brand file")
        self.assertFalse((SITE / "CNAME").exists())
        self.assertFalse((self.out / "CNAME").exists())

    def test_index_carries_the_line_the_stack_the_licence_and_the_three_links(self):
        index = parsed(self.out / "index.html")
        words = index.words()
        for phrase in LINE:
            self.assertIn(phrase, words.lower())
        for item in ("Java 25", "Vert.x 5.1", "PostgreSQL", "ClickHouse", "Valkey", "S3", "Apache 2.0"):
            self.assertIn(item, words)
        for url in ("https://github.com/subapavel/rail8",
                    "https://github.com/subapavel/rail8/blob/main/docs/QUICKSTART.md",
                    "https://github.com/subapavel/rail8/blob/main/docs/ARCHITECTURE.md"):
            self.assertIn(url, index.hrefs)

    def test_the_description_meta_of_index_holds_the_whole_line(self):
        description = parsed(self.out / "index.html").metas.get("description")
        self.assertIsNotNone(description, "index.html has no description meta")
        normalised = re.sub(r"\s+", " ", description.replace("\xa0", " ")).strip()
        self.assertEqual("Rail8 — a modern open-source alternative to legacy integration middleware: Java 25 virtual"
                         " threads, Vert.x 5, fast by design, every message traceable end to end. Apache 2.0.",
                         normalised)

    def test_no_file_of_the_built_site_holds_the_refused_line(self):
        for name, data in self.files(self.out).items():
            text = re.sub(rb"\s+", b" ", data.lower())
            self.assertNotIn(b"monitoring is the product", text, f"{name} holds the refused line")

    def test_index_links_a_favicon_and_the_touch_icon_from_brand(self):
        links = parsed(self.out / "index.html").links
        self.assertTrue(any("icon" in rel and href.startswith("brand/favicon") for rel, href in links), links)
        self.assertIn((["apple-touch-icon"], "brand/apple-touch-icon.png"), links)

    def test_every_page_has_headings_starting_at_h1_that_never_skip_a_level(self):
        for name in ("index.html", "404.html"):
            levels = parsed(self.out / name).headings
            self.assertTrue(levels, f"{name} has no heading")
            self.assertEqual(1, levels[0], f"{name} headings {levels}")
            for before, after in zip(levels, levels[1:]):
                self.assertLessEqual(after, before + 1, f"{name} skips a heading level: {levels}")

    def test_the_style_sheet_has_a_dark_colour_scheme(self):
        css = (self.out / "style.css").read_text(encoding="utf-8")
        self.assertRegex(css, r"prefers-color-scheme\s*:\s*dark")


if __name__ == "__main__":
    unittest.main()
