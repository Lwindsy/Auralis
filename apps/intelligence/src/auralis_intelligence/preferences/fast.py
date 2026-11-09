"""Conservative generic-request fast path; substantive text retains full interpretation."""

import re
from copy import deepcopy

from auralis_intelligence.contracts import content_digest, validate_definition

from .reference import PreferenceError
from .stage import PreferencesStage

GENERIC = re.compile(
    r"(?:请|麻烦|可以)?(?:给我|帮我)?(?:推荐|推|找|来)(?:一些|几张|点|几款)?(?:新的?|好听的?)?(?:专辑|音乐|歌)"
    r"(?:[，,、\s]*(?:符合|适合|按|根据)(?:我|我的)(?:的)?(?:品味|口味|偏好|喜好)(?:的)?)?[。！!？?\s]*"
    r"|(?:please\s+)?(?:recommend|suggest)(?:\s+me)?\s+(?:some\s+)?(?:new\s+)?(?:albums|music)"
    r"(?:\s+(?:for me|based on my taste))?[.!?\s]*"
    r"|(?:随便|随意|都行|都可以|随便听听|随便推荐|随便推点|随便推荐几张专辑|随便来点音乐"
    r"|anything|anything is fine|surprise me)[。！!？?\s]*",
    re.I,
)


class FastPreferencesStage(PreferencesStage):
    async def __call__(self, execution):
        raw = execution.start["input"].get("raw")
        if raw is None or not GENERIC.fullmatch(raw["request_text"].strip()):
            return await super().__call__(execution)
        # A generic discovery request does not add genres or override explicit input.
        with execution.clock.measure("interpret"):
            proposal = {
                "input_digest": raw["input_digest"],
                "reference_revision": self.reference.revision,
                "parser_revision": "generic-discovery.v2",
                "preferences": [],
                "constraints": [],
                "unresolved_constraints": [],
                "soft_preferences": [
                    {
                        "preference_id": "text:discovery",
                        "description": raw["request_text"],
                        "kind": "context",
                        "basis": "explicit",
                        "strength": "normal",
                        "focused": False,
                    }
                ],
            }
            proposal["proposal_digest"] = content_digest(proposal, "proposal_digest")
            validate_definition("PreferenceResolutionProposal", proposal)
            accepted = await execution.control_request("preference-resolution", proposal)
            validate_definition("EffectivePreferenceSnapshot", accepted)
            if any(
                accepted[k] != proposal[k]
                for k in ("input_digest", "reference_revision", "parser_revision")
            ):
                raise PreferenceError("PREFERENCE_BINDING_MISMATCH")
            execution.start["input"]["accepted_preferences"] = deepcopy(accepted)
            return accepted
