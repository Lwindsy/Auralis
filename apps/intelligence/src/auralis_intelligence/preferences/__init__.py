"""Two-pass proposals and deterministic rules for a synthetic Java resolver."""

from .reference import PreferenceError, ReferenceCatalog
from .resolution import merge_preferences
from .stage import PreferencesStage

__all__ = ["PreferenceError", "ReferenceCatalog", "PreferencesStage", "merge_preferences"]
