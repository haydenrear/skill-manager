
  # One venv per tool: a shared venv would let one tool's reinstall pull the
  # interpreter out from under another tool's launcher.
  local install_root="$SKILL_MANAGER_CACHE_DIR/skill-script-$skill_name-$tool"
  local venv_dir="$install_root/venv"
  local cli_bin_dir="${SKILL_MANAGER_HOME:-$(cd "$SKILL_MANAGER_BIN_DIR/.." && pwd)}/bin/cli"
  local launcher="$cli_bin_dir/$tool"

  rm -rf "$install_root"
  mkdir -p "$install_root" "$cli_bin_dir"

  "$python_bin" -m venv "$venv_dir"
  "$venv_dir/bin/python" -m pip install --upgrade pip
  "$venv_dir/bin/python" -m pip install "$SKILL_DIR"

  if [ ! -x "$venv_dir/bin/$tool" ]; then
    echo "$tool is not a console script of the installed package" >&2
    return 1
  fi

  cat >"$launcher" <<EOF
#!/usr/bin/env bash
export MONITORING_DEPLOY_CDC_ROOT="$SKILL_DIR"
exec "$venv_dir/bin/$tool" "\$@"
EOF
  chmod 0755 "$launcher"

  "$launcher" --help >/dev/null
  echo "Installed $tool to $launcher"
}
