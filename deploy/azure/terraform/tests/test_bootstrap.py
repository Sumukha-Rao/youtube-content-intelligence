"""Offline bootstrap contract checks; no Azure login or infrastructure changes.

Run: python3 deploy/azure/terraform/tests/test_bootstrap.py
Requires Terraform, Docker Compose, and PyYAML.
"""

import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[4]
TEMPLATE = ROOT / "deploy/azure/terraform/cloud-init.yaml.tftpl"


class BootstrapTest(unittest.TestCase):
    def render(self, **overrides):
        fixture = dict(
            admin_username="azureuser", swap_size_gb=4,
            repo_url="https://github.com/example/project.git", repo_branch="master",
            load_schema=True, compose_args="", site_address="example.southindia.cloudapp.azure.com",
            mysql_host="example.mysql.database.azure.com", mysql_database="custom_yci",
            mysql_url="jdbc:mysql://example.mysql.database.azure.com/custom_yci?sslMode=REQUIRED&serverTimezone=UTC",
            mysql_user="yciadmin", mysql_password="Synthetic%Password_123!", ddl_auto="update",
            app_secret_key="synthetic-encryption-key", youtube_api_key="synthetic-youtube-key",
            youtube_max_comments=200, llm_base_url="https://provider.example/v1",
            llm_api_key="synthetic-model-key", llm_model="test-model", llm_timeout_seconds=900,
            llm_max_prompt_chars=24000, web_search_provider="duckduckgo", web_search_base_url="",
            web_search_fallback=True, searxng_secret="synthetic-search-secret", auth_token_ttl_days=30,
            smtp_host="smtp.example.com", smtp_port=587, smtp_username="sender@example.com",
            smtp_password="synthetic-mail-password", notify_from="Ideas <sender@example.com>",
            notify_cron="0 0 * * * *",
        )
        fixture.update(overrides)
        with tempfile.TemporaryDirectory(prefix="yci-bootstrap-test-") as directory:
            work = Path(directory)
            shutil.copyfile(TEMPLATE, work / "cloud-init.yaml.tftpl")
            (work / "fixture.json").write_text(json.dumps(fixture))
            (work / "main.tf").write_text('''locals {
  rendered = templatefile("${path.module}/cloud-init.yaml.tftpl", jsondecode(file("${path.module}/fixture.json")))
}
''')
            result = subprocess.run(["terraform", "console", "-no-color"],
                input="jsonencode(yamldecode(local.rendered))\n", text=True,
                cwd=work, capture_output=True, check=True)
            config = json.loads(json.loads(result.stdout.strip()))
        files = {}
        for file in config["write_files"]:
            content = file["content"]
            if file.get("encoding") == "b64":
                content = base64.b64decode(content).decode()
            files[file["path"]] = content
        return fixture, config, files

    def compose(self, content):
        with tempfile.TemporaryDirectory(prefix="yci-dotenv-test-") as directory:
            env_file = Path(directory) / "fixture.env"
            env_file.write_text(content)
            env_file.chmod(0o600)
            environment = os.environ.copy()
            for line in content.splitlines():
                if "=" in line:
                    environment.pop(line.split("=", 1)[0], None)
            result = subprocess.run(["docker", "compose", "--env-file", str(env_file),
                "-f", "deploy/azure/docker-compose.azure.yml", "config", "--format", "json"],
                cwd=ROOT, env=environment, text=True, capture_output=True, check=True)
            return json.loads(result.stdout)

    def runtime_values(self, content):
        # Compose config escapes dollars again when serializing its output. Check
        # actual container values too, using only synthetic data and a tiny image.
        with tempfile.TemporaryDirectory(prefix="yci-runtime-env-test-") as directory:
            work = Path(directory)
            (work / ".env").write_text(content)
            (work / ".env").chmod(0o600)
            deployment = yaml.safe_load((ROOT / "deploy/azure/docker-compose.azure.yml").read_text())
            compose = {"services": {"check": {
                "image": "busybox", "network_mode": "none",
                "environment": deployment["services"]["backend"]["environment"],
                "entrypoint": ["sh", "-c", 'printf "%s\\0" "$$LLM_API_KEY" "$$SMTP_PASSWORD" "$$SPRING_DATASOURCE_URL" "$$NOTIFY_FROM"'],
            }}}
            (work / "compose.yml").write_text(yaml.safe_dump(compose))
            environment = os.environ.copy()
            for line in content.splitlines():
                if "=" in line:
                    environment.pop(line.split("=", 1)[0], None)
            result = subprocess.run(["docker", "compose", "--project-name", "yci-bootstrap-check",
                "--env-file", str(work / ".env"), "-f", str(work / "compose.yml"),
                "run", "--rm", "-T", "--no-deps", "check"],
                env=environment, capture_output=True, check=True)
            return result.stdout.decode().split("\0")[:-1]

    def test_rendered_shell_is_valid_and_failures_propagate(self):
        _, config, files = self.render(repo_url="https://example.com/owner's-repo.git", repo_branch="feature/it's-tested")
        script = files["/usr/local/bin/yci-bootstrap.sh"]
        subprocess.run(["bash", "-n"], input=script, text=True, check=True)
        self.assertIn("pipefail", config["runcmd"][0])
        self.assertIn('mysql --defaults-extra-file=/opt/yci/.my.cnf "$MYSQL_DATABASE"', script)
        self.assertIn('if [ "$TABLES" -eq 0 ]', script)

    def test_compose_preserves_special_characters_and_keeps_services_private(self):
        special = 'quote" apostrophe\' slash\\ dollars$${LITERAL} single$ amp& angle<> newline\nnext'
        fixture, config, files = self.render(llm_api_key=special, smtp_password=special)
        compose = self.compose(files["/opt/yci/.env"])
        backend = compose["services"]["backend"]["environment"]
        self.assertEqual(fixture["llm_api_key"].replace("$", "$$"), backend["LLM_API_KEY"])
        self.assertEqual(fixture["smtp_password"].replace("$", "$$"), backend["SMTP_PASSWORD"])
        self.assertEqual(fixture["mysql_url"], backend["SPRING_DATASOURCE_URL"])
        self.assertEqual(fixture["notify_from"], backend["NOTIFY_FROM"])
        self.assertEqual([fixture[key] for key in ("llm_api_key", "smtp_password", "mysql_url", "notify_from")],
                         self.runtime_values(files["/opt/yci/.env"]))
        for name in ("backend", "frontend", "ml-service"):
            self.assertFalse(compose["services"][name].get("ports"))
        for file in config["write_files"]:
            if file["path"] in ("/opt/yci/.env", "/opt/yci/.my.cnf"):
                self.assertEqual("0600", file["permissions"])

    def test_optional_search_profile_renders(self):
        _, _, files = self.render(compose_args="--profile searxng", web_search_provider="searxng",
                                  web_search_base_url="http://searxng:8080")
        script = files["/usr/local/bin/yci-bootstrap.sh"]
        subprocess.run(["bash", "-n"], input=script, text=True, check=True)
        self.assertIn('COMPOSE_ARGS="--profile searxng"', script)
        self.assertEqual("searxng", self.compose(files["/opt/yci/.env"])["services"]["backend"]["environment"]["WEB_SEARCH_PROVIDER"])

    def test_smtp_relay_overrides_reach_backend(self):
        _, _, files = self.render()
        content = files["/opt/yci/.env"] + "\nSMTP_AUTH=false\nSMTP_STARTTLS=false\n"
        backend = self.compose(content)["services"]["backend"]["environment"]
        self.assertEqual("false", backend["SMTP_AUTH"])
        self.assertEqual("false", backend["SMTP_STARTTLS"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
