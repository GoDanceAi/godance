#!/usr/bin/env python3
"""Checks that .env.example covers what the self-hosted stack reads.

Run from anywhere: python3 deploy/self-host/check-env.py
CI runs it (.github/workflows/self-host.yml) after `docker compose config`.

It fails when
  1. a compose file uses ${VAR} (or $VAR) that .env.example does not define;
  2. Caddy's configuration reads {$VAR} that no compose file passes to the
     web container, or that .env.example does not define;
  3. application-prod.yml has a placeholder with no default (the API does not
     start without it) that neither .env.example nor the API's environment
     in docker-compose.yml supplies;
  4. .env.example defines a variable twice.
Standard library only.
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
COMPOSE_FILES = sorted(HERE.glob("docker-compose*.yml"))
CADDY_FILES = [HERE / "Caddyfile", *sorted(HERE.glob("*.caddy"))]
ENV_EXAMPLE = HERE / ".env.example"
PROD_YAML = ROOT / "backend/src/main/resources/application-prod.yml"

problems = []


def defined_in_env_example():
    names = []
    for line in ENV_EXAMPLE.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)=", line)
        if match:
            names.append(match.group(1))
    for name in sorted({n for n in names if names.count(n) > 1}):
        problems.append(f".env.example defines {name} more than once")
    return set(names)


def without_comments(text):
    """Drops `# ...` comments: a # at the start of a line or after whitespace."""
    return "\n".join(re.sub(r"(^|\s)#.*$", "", line) for line in text.splitlines())


def compose_variables(text):
    """${VAR...} and $VAR, skipping $$ (compose's escape for a literal $)."""
    braced = re.findall(r"(?<!\$)\$\{([A-Za-z_][A-Za-z0-9_]*)", text)
    bare = re.findall(r"(?<![$\w])\$([A-Za-z_][A-Za-z0-9_]*)", text)
    return set(braced) | set(bare)


def environment_keys(text):
    """Keys set in `environment:` mappings (NAME: value lines)."""
    return set(re.findall(r"^\s+([A-Z][A-Z0-9_]*):\s", text, flags=re.MULTILINE))


env_example = defined_in_env_example()
compose_texts = {path.name: without_comments(path.read_text(encoding="utf-8")) for path in COMPOSE_FILES}
passed_to_containers = set().union(*(environment_keys(t) for t in compose_texts.values()))

for name, text in compose_texts.items():
    for variable in sorted(compose_variables(text) - env_example):
        problems.append(f"{name} uses ${{{variable}}}, which .env.example does not define")

for path in CADDY_FILES:
    for variable in sorted(set(re.findall(r"\{\$([A-Za-z_][A-Za-z0-9_]*)", without_comments(path.read_text(encoding="utf-8"))))):
        if variable not in passed_to_containers:
            problems.append(f"{path.name} reads {{${variable}}}, which no compose file passes to the web container")
        if variable not in env_example:
            problems.append(f"{path.name} reads {{${variable}}}, which .env.example does not define")

api_environment = environment_keys(compose_texts["docker-compose.yml"])
for match in re.finditer(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(:[^}]*)?\}", PROD_YAML.read_text(encoding="utf-8")):
    variable, default = match.group(1), match.group(2)
    if default is None and variable not in env_example and variable not in api_environment:
        problems.append(f"application-prod.yml requires {variable} (no default), "
                        "but neither .env.example nor docker-compose.yml supplies it")

if problems:
    print("check-env: .env.example does not cover the stack:", file=sys.stderr)
    for problem in problems:
        print("  - " + problem, file=sys.stderr)
    sys.exit(1)
print(f"check-env: OK. .env.example ({len(env_example)} variables) covers "
      f"{', '.join(path.name for path in COMPOSE_FILES + CADDY_FILES)} and application-prod.yml")
