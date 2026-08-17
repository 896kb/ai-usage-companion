#!/usr/bin/env python3
"""Render README.md the way GitLab will, and open it.

    python3 tools/readme-preview.py

Writes .readme-preview.html (git-ignored). Needs `markdown`; if it is missing this
builds a throwaway venv under the system temp dir rather than touching your Python.

The one thing this does beyond a plain renderer: GitLab turns an image-syntax link to a
video file into a real <video> player, and GitHub does not. Since this repo is published
to GitLab, the preview must do the same or it misrepresents the page.
"""
import pathlib
import re
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
OUT = REPO / ".readme-preview.html"
VIDEO = (".mp4", ".m4v", ".mov", ".webm", ".ogv")     # what GitLab embeds

CSS = """
 :root { color-scheme: dark; }
 body { background:#16181a; color:#c5c8c6; font:16px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
        max-width:820px; margin:0 auto; padding:48px 28px 120px; }
 h1 { font-size:2.1em; line-height:1.2; color:#fff; border-bottom:1px solid #2f3234; padding-bottom:.35em; }
 h2 { font-size:1.45em; color:#b5bd68; margin-top:2.2em; border-bottom:1px solid #2f3234; padding-bottom:.3em; }
 h3 { font-size:1.12em; color:#ffff78; margin-top:1.8em; }
 a { color:#81a2be; }
 code { background:#1d1f21; padding:.15em .4em; border-radius:4px; font-size:.9em; }
 pre { background:#1d1f21; padding:14px 16px; border-radius:8px; overflow-x:auto; border:1px solid #2a2c2e; }
 pre code { background:none; padding:0; }
 table { border-collapse:collapse; width:100%; margin:1em 0; display:block; overflow-x:auto; }
 th,td { border:1px solid #2f3234; padding:8px 12px; text-align:left; vertical-align:middle; }
 th { background:#1d1f21; }
 img { max-width:100%; image-rendering:pixelated; }
 video { max-width:100%; border-radius:10px; display:block; margin:1.2em 0; }
 blockquote { border-left:3px solid #f0c674; margin:1.4em 0; padding:.2em 1em; background:#1d1f21; color:#e0e0e0; }
 strong { color:#fff; }
 hr { border:0; border-top:1px solid #2f3234; margin:2.4em 0; }
"""


def renderer():
    try:
        import markdown
        return markdown
    except ImportError:
        pass
    venv = pathlib.Path(tempfile.gettempdir()) / "readme-preview-venv"
    if not venv.exists():
        print("  building a throwaway venv (system Python left alone)")
        subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
        subprocess.run([str(venv / "bin/pip"), "install", "--quiet", "markdown"], check=True)
    subprocess.run([str(venv / "bin/python"), __file__] + sys.argv[1:], check=True)
    raise SystemExit(0)


def main():
    markdown = renderer()
    src = (REPO / "README.md").read_text()
    html = markdown.markdown(src, extensions=["tables", "fenced_code", "sane_lists"])

    # <img src="…mp4"> -> a real player, as GitLab does
    def to_video(m):
        src_attr = m.group(1)
        if src_attr.lower().endswith(VIDEO):
            return f'<video src="{src_attr}" controls muted loop playsinline></video>'
        return m.group(0)

    html = re.sub(r'<img [^>]*src="([^"]+)"[^>]*/?>', to_video, html)

    OUT.write_text(f'<!doctype html><meta charset="utf-8">'
                   f'<title>README — {REPO.name}</title><style>{CSS}</style>\n{html}')
    print(f"  wrote {OUT.relative_to(REPO)}")
    subprocess.run(["open", str(OUT)])


if __name__ == "__main__":
    main()
