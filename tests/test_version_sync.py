import tempfile
import unittest
from pathlib import Path

import version_sync


class VersionSyncTest(unittest.TestCase):

    def test_plugin_version_bump_does_not_change_api_version(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            plugin_yml = repo / "src/main/resources/plugin.yml"
            plugin_yml.parent.mkdir(parents=True)
            plugin_yml.write_text(
                'name: xApocalypse\nversion: "1.7.1"\napi-version: "1.21.11"\n',
                encoding="utf-8",
            )

            changes = version_sync.compute_changes(repo, "1.7.2")
            version_sync.apply_changes(repo, changes)

            self.assertEqual(
                'name: xApocalypse\nversion: "1.7.2"\napi-version: "1.21.11"\n',
                plugin_yml.read_text(encoding="utf-8"),
            )


if __name__ == "__main__":
    unittest.main()
