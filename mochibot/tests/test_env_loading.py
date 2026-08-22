"""Tests for environment variable loading and validation."""

import os
from unittest.mock import MagicMock, patch
import pytest

from bot import clean_env_var, load_environment, create_bot


def test_clean_env_var():
    assert clean_env_var("  hello  ") == "hello"
    assert clean_env_var('"hello"') == "hello"
    assert clean_env_var("'hello'") == "hello"
    assert clean_env_var(' "hello" ') == "hello"
    assert clean_env_var(' "" ') is None
    assert clean_env_var(None) is None
    assert clean_env_var("") is None


def test_load_environment_finds_file(tmp_path, monkeypatch):
    env_file = tmp_path / ".env"
    env_file.write_text("TEST_MOCHI_KEY=mochi_val_123\n")

    monkeypatch.chdir(tmp_path)
    loaded = load_environment()
    assert env_file.resolve() in loaded
    assert os.getenv("TEST_MOCHI_KEY") == "mochi_val_123"


def test_create_bot_missing_vars(monkeypatch):
    monkeypatch.delenv("API_ID", raising=False)
    monkeypatch.delenv("API_HASH", raising=False)
    monkeypatch.delenv("BOT_TOKEN", raising=False)

    with pytest.raises(SystemExit) as exc:
        create_bot()
    assert exc.value.code == 1


def test_create_bot_placeholder_vars(monkeypatch):
    monkeypatch.setenv("API_ID", "your_api_id_here")
    monkeypatch.setenv("API_HASH", "your_api_hash_here")
    monkeypatch.setenv("BOT_TOKEN", "your_bot_token_here")

    with pytest.raises(SystemExit) as exc:
        create_bot()
    assert exc.value.code == 1


def test_create_bot_valid_vars(monkeypatch):
    monkeypatch.setenv("API_ID", "1234567")
    monkeypatch.setenv("API_HASH", "abcdef1234567890abcdef1234567890")
    monkeypatch.setenv("BOT_TOKEN", "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11")
    monkeypatch.setenv("OWNER_ID", "987654321")

    with patch("bot.Client") as mock_client:
        mock_instance = MagicMock()
        mock_client.return_value = mock_instance
        app, token, owner_id = create_bot()
        assert token == "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
        assert owner_id == 987654321
        assert app == mock_instance
