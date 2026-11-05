#!/usr/bin/env python3
"""
version_sync.py — One-stop version bumper for xApocalypse.

Type a new version (and a commit message) and this rewrites every place the
version is duplicated — plugin.yml, pom.xml, config.yml, messages.yml, the
README and the docs/ wiki — then stages just those files and commits.

The replacement rules match on the *shape* of each reference (e.g. a badge URL,
a jar filename, a header comment) rather than the literal current number, so a
single run also fixes any drift where files disagree about the version.

Usage:
    py version_sync.py                       # launch the GUI (default)
    py version_sync.py --version 1.0.2 --message "Version 1.0.2: ..." [--push]
    py version_sync.py --version 1.0.2 --dry-run     # preview, write nothing
    py version_sync.py --show                # just print the detected version
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# --------------------------------------------------------------------------- #
# Replacement rules
# --------------------------------------------------------------------------- #

# A version token: 1.2.3 with an optional pre-release suffix (e.g. 1.0.2-SNAPSHOT).
VERSION_TOKEN = r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?"

# Anything that should be accepted from the GUI / CLI as a new version.
VERSION_INPUT_RE = re.compile(r"^\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?$")


def _make_rule(path: str, pre: str, suf: str = "") -> tuple[str, re.Pattern]:
    """Build a (relative_path, compiled_pattern) rule.

    ``pre`` and ``suf`` are regex snippets that bracket the version token. They
    are what scope the match — e.g. the pom rule only fires after the project's
    own <artifactId>, never on a dependency's <version>.
    """
    pattern = re.compile(
        "(?P<pre>" + pre + ")(?P<ver>" + VERSION_TOKEN + ")(?P<suf>" + suf + ")",
        re.MULTILINE,
    )
    return path, pattern


# Every place the version lives. Add a line here to cover a new reference.
RULES: list[tuple[str, re.Pattern]] = [
    # plugin.yml — the canonical source: version: "1.0.1"
    _make_rule("src/main/resources/plugin.yml", r'^version:\s*"', r'"'),
    # pom.xml — the project version only (scoped by the project's artifactId)
    _make_rule(
        "pom.xml",
        r"<artifactId>xapocalypse</artifactId>\s*<version>",
        r"</version>",
    ),
    # config.yml — "# xApocalypse v1.0.0 - Balanced Configuration"
    _make_rule("src/main/resources/config.yml", r"# xApocalypse v"),
    # messages.yml — header comment and the in-game help-menu title
    _make_rule("src/main/resources/messages.yml", r"# xApocalypse v"),
    _make_rule("src/main/resources/messages.yml", r"xApocalypse &r&7v"),
    # README.md — shields.io version badge and the jar download line
    _make_rule("README.md", r"Version-", r"-brightgreen"),
    _make_rule("README.md", r"xApocalypse-", r"\.jar"),
    # docs/ wiki — Home badge and the two Getting-Started jar references
    _make_rule("docs/Home.md", r"Version-", r"-brightgreen"),
    _make_rule("docs/Getting-Started.md", r"xApocalypse-", r"\.jar"),
    _make_rule("docs/PlaceholderAPI.md", r"xApocalypse\s+", r"\s+provides"),
]

# The plugin.yml version line is the source of truth for "current version".
_CURRENT_RE = re.compile(r'^version:\s*"(' + VERSION_TOKEN + r')"', re.MULTILINE)
_PLUGIN_YML = "src/main/resources/plugin.yml"


# --------------------------------------------------------------------------- #
# Core logic (shared by GUI and CLI)
# --------------------------------------------------------------------------- #


class FileChange:
    """The result of applying every rule for a single file."""

    def __init__(self, path: str):
        self.path = path
        self.exists = True
        self.subs = 0
        self.old_versions: set[str] = set()
        self.new_text: str | None = None  # populated only if something changed

    @property
    def changed(self) -> bool:
        return self.subs > 0


def detect_current_version(repo: Path) -> str | None:
    """Read the canonical version from plugin.yml, or None if unreadable."""
    f = repo / _PLUGIN_YML
    if not f.exists():
        return None
    m = _CURRENT_RE.search(f.read_text(encoding="utf-8"))
    return m.group(1) if m else None


def compute_changes(repo: Path, new_version: str) -> list[FileChange]:
    """Compute (without writing) what each file would become."""
    # Group rules by file so each file is read/processed once.
    by_file: dict[str, list[re.Pattern]] = {}
    order: list[str] = []
    for path, pattern in RULES:
        if path not in by_file:
            by_file[path] = []
            order.append(path)
        by_file[path].append(pattern)

    changes: list[FileChange] = []
    for path in order:
        change = FileChange(path)
        f = repo / path
        if not f.exists():
            change.exists = False
            changes.append(change)
            continue

        text = f.read_text(encoding="utf-8")
        new_text = text
        for pattern in by_file[path]:

            def _repl(m: re.Match) -> str:
                change.subs += 1
                change.old_versions.add(m.group("ver"))
                return m.group("pre") + new_version + m.group("suf")

            new_text = pattern.sub(_repl, new_text)

        if new_text != text:
            change.new_text = new_text
        changes.append(change)

    return changes


def apply_changes(repo: Path, changes: list[FileChange]) -> list[str]:
    """Write every changed file to disk. Returns the list of written paths."""
    written: list[str] = []
    for change in changes:
        if change.changed and change.new_text is not None:
            (repo / change.path).write_text(change.new_text, encoding="utf-8")
            written.append(change.path)
    return written


# --------------------------------------------------------------------------- #
# Git helpers
# --------------------------------------------------------------------------- #


def git(repo: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        text=True,
    )


def is_git_repo(repo: Path) -> bool:
    return git(repo, "rev-parse", "--is-inside-work-tree").returncode == 0


def current_branch(repo: Path) -> str:
    r = git(repo, "rev-parse", "--abbrev-ref", "HEAD")
    return r.stdout.strip() if r.returncode == 0 else "?"


def stage_and_commit(
    repo: Path, paths: list[str], message: str, push: bool
) -> tuple[bool, str]:
    """Stage the given paths, commit, and optionally push. Returns (ok, log)."""
    lines: list[str] = []

    add = git(repo, "add", "--", *paths)
    if add.returncode != 0:
        return False, "git add failed:\n" + (add.stderr or add.stdout)
    lines.append("staged: " + ", ".join(paths))

    commit = git(repo, "commit", "-m", message)
    out = (commit.stdout + commit.stderr).strip()
    if commit.returncode != 0:
        return False, "git commit failed:\n" + out
    lines.append(out)

    if push:
        push_r = git(repo, "push")
        lines.append((push_r.stdout + push_r.stderr).strip() or "git push: done")
        if push_r.returncode != 0:
            return False, "\n".join(lines)

    return True, "\n".join(lines)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def _format_change(c: FileChange) -> str:
    if not c.exists:
        return f"  [skip] {c.path} - file not found"
    if not c.changed:
        return f"  [ ! ]  {c.path} - no version reference matched"
    olds = ", ".join(sorted(c.old_versions))
    return f"  [ ok ] {c.path} - {c.subs} replacement(s) (was {olds})"


def run_cli(args: argparse.Namespace) -> int:
    repo = Path(args.repo).resolve()
    current = detect_current_version(repo)

    if args.show:
        print(current or "(version not found)")
        return 0

    if not VERSION_INPUT_RE.match(args.version or ""):
        print(f"error: '{args.version}' is not a valid version (expected e.g. 1.0.2)")
        return 2

    changes = compute_changes(repo, args.version)
    print(f"Repo:    {repo}")
    print(f"Current: {current}")
    print(f"New:     {args.version}\n")
    for c in changes:
        print(_format_change(c))

    touched = [c.path for c in changes if c.changed]
    if not touched:
        print("\nNothing to change.")
        return 0

    if args.dry_run:
        print("\n(dry run — no files written)")
        return 0

    written = apply_changes(repo, changes)
    print(f"\nWrote {len(written)} file(s).")

    if args.message:
        ok, log = stage_and_commit(repo, written, args.message, args.push)
        print(log)
        return 0 if ok else 1

    print("(no --message given; files written but not committed)")
    return 0


# --------------------------------------------------------------------------- #
# GUI
# --------------------------------------------------------------------------- #


def run_gui(repo_default: Path) -> int:
    import tkinter as tk
    from tkinter import filedialog, messagebox, scrolledtext, ttk

    root = tk.Tk()
    root.title("xApocalypse — Version Sync")
    root.geometry("720x620")
    root.minsize(640, 540)

    state = {"repo": repo_default}

    pad = {"padx": 10, "pady": 4}
    frm = ttk.Frame(root, padding=12)
    frm.pack(fill="both", expand=True)
    frm.columnconfigure(1, weight=1)

    # --- Repo row ---------------------------------------------------------- #
    ttk.Label(frm, text="Repository:").grid(row=0, column=0, sticky="w", **pad)
    repo_var = tk.StringVar(value=str(repo_default))
    repo_entry = ttk.Entry(frm, textvariable=repo_var)
    repo_entry.grid(row=0, column=1, sticky="ew", **pad)

    def browse():
        chosen = filedialog.askdirectory(initialdir=repo_var.get())
        if chosen:
            repo_var.set(chosen)
            refresh_current()

    ttk.Button(frm, text="Browse…", command=browse).grid(row=0, column=2, **pad)

    # --- Current version --------------------------------------------------- #
    ttk.Label(frm, text="Current version:").grid(row=1, column=0, sticky="w", **pad)
    current_var = tk.StringVar(value="—")
    ttk.Label(frm, textvariable=current_var, font=("Segoe UI", 10, "bold")).grid(
        row=1, column=1, sticky="w", **pad
    )

    branch_var = tk.StringVar(value="")
    ttk.Label(frm, textvariable=branch_var, foreground="#666").grid(
        row=1, column=2, sticky="e", **pad
    )

    # --- New version ------------------------------------------------------- #
    ttk.Label(frm, text="New version:").grid(row=2, column=0, sticky="w", **pad)
    new_var = tk.StringVar()
    new_entry = ttk.Entry(frm, textvariable=new_var)
    new_entry.grid(row=2, column=1, sticky="ew", **pad)

    # --- Commit message ---------------------------------------------------- #
    ttk.Label(frm, text="Commit message:").grid(row=3, column=0, sticky="nw", **pad)
    msg_text = tk.Text(frm, height=3, wrap="word")
    msg_text.grid(row=3, column=1, columnspan=2, sticky="ew", **pad)

    # --- Options ----------------------------------------------------------- #
    push_var = tk.BooleanVar(value=False)
    opts = ttk.Frame(frm)
    opts.grid(row=4, column=1, columnspan=2, sticky="w", **pad)
    ttk.Checkbutton(opts, text="git push after commit", variable=push_var).pack(
        side="left"
    )

    # --- Log --------------------------------------------------------------- #
    log = scrolledtext.ScrolledText(frm, height=14, wrap="word", state="disabled")
    log.grid(row=6, column=0, columnspan=3, sticky="nsew", **pad)
    frm.rowconfigure(6, weight=1)

    def write_log(msg: str, clear: bool = False):
        log.configure(state="normal")
        if clear:
            log.delete("1.0", "end")
        log.insert("end", msg + "\n")
        log.see("end")
        log.configure(state="disabled")

    # --- Helpers ----------------------------------------------------------- #
    def get_repo() -> Path:
        return Path(repo_var.get()).resolve()

    def refresh_current():
        repo = get_repo()
        cur = detect_current_version(repo)
        current_var.set(cur or "(not found)")
        if is_git_repo(repo):
            branch_var.set(f"branch: {current_branch(repo)}")
        else:
            branch_var.set("not a git repo")
        if cur and not new_var.get():
            new_var.set(cur)

    def validate() -> tuple[Path, str] | None:
        repo = get_repo()
        version = new_var.get().strip()
        if not VERSION_INPUT_RE.match(version):
            messagebox.showerror(
                "Invalid version",
                f"'{version}' is not a valid version.\nExpected something like 1.0.2",
            )
            return None
        return repo, version

    def do_preview():
        checked = validate()
        if not checked:
            return
        repo, version = checked
        changes = compute_changes(repo, version)
        write_log(f"Preview — new version {version}", clear=True)
        for c in changes:
            write_log(_format_change(c))
        touched = [c for c in changes if c.changed]
        write_log("")
        if touched:
            write_log(f"{len(touched)} file(s) will change. Click Apply & Commit.")
        else:
            write_log("Nothing would change.")

    def do_apply():
        checked = validate()
        if not checked:
            return
        repo, version = checked

        if not is_git_repo(repo):
            messagebox.showerror("Not a git repo", f"{repo} is not a git repository.")
            return

        message = msg_text.get("1.0", "end").strip()
        if not message:
            message = f"Version {version}"

        changes = compute_changes(repo, version)
        touched = [c for c in changes if c.changed]
        if not touched:
            messagebox.showinfo("Nothing to do", "No version references changed.")
            return

        warnings = [c.path for c in changes if c.exists and not c.changed]
        summary = (
            f"Update {len(touched)} file(s) to v{version} and commit?\n\n"
            + "\n".join(f"  • {c.path}" for c in touched)
        )
        if warnings:
            summary += "\n\nNo match (left untouched):\n" + "\n".join(
                f"  • {p}" for p in warnings
            )
        summary += f"\n\nCommit message:\n  {message}"
        if push_var.get():
            summary += "\n\nWill also: git push"
        if not messagebox.askyesno("Confirm", summary):
            return

        write_log(f"Applying v{version}…", clear=True)
        written = apply_changes(repo, changes)
        for c in changes:
            write_log(_format_change(c))
        write_log("")

        ok, git_log = stage_and_commit(repo, written, message, push_var.get())
        write_log(git_log)
        if ok:
            write_log("\n✅ Done.")
            refresh_current()
            messagebox.showinfo("Success", f"Committed v{version}.")
        else:
            write_log("\n❌ Git step failed — files were written but not committed.")
            messagebox.showerror("Git failed", git_log)

    # --- Buttons ----------------------------------------------------------- #
    btns = ttk.Frame(frm)
    btns.grid(row=5, column=0, columnspan=3, sticky="e", **pad)
    ttk.Button(btns, text="Preview", command=do_preview).pack(side="left", padx=4)
    ttk.Button(btns, text="Apply & Commit", command=do_apply).pack(side="left", padx=4)
    ttk.Button(btns, text="Refresh", command=refresh_current).pack(side="left", padx=4)

    refresh_current()
    write_log("Ready. Enter a new version, then Preview or Apply & Commit.")
    root.mainloop()
    return 0


# --------------------------------------------------------------------------- #
# Entry point
# --------------------------------------------------------------------------- #


def main() -> int:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Bump xApocalypse version everywhere.")
    parser.add_argument("--repo", default=str(here), help="repo root (default: script dir)")
    parser.add_argument("--version", help="new version, e.g. 1.0.2")
    parser.add_argument("--message", help="commit message (omit to write without committing)")
    parser.add_argument("--push", action="store_true", help="git push after commit")
    parser.add_argument("--dry-run", action="store_true", help="preview only, write nothing")
    parser.add_argument("--show", action="store_true", help="print detected version and exit")
    parser.add_argument("--gui", action="store_true", help="force the GUI")
    args = parser.parse_args()

    # CLI mode if any non-GUI action was requested; otherwise launch the GUI.
    if args.show or args.version or args.dry_run:
        return run_cli(args)
    return run_gui(Path(args.repo).resolve())


if __name__ == "__main__":
    sys.exit(main())
