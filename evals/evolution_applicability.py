"""Select diagnostic lessons from caller-verified, pre-answer observations.

The catalog belongs to the observation producer. A lesson cannot invent new
conditions, and a match never grants permission to load a production method.
"""
from evolution_candidates import experience_matches, validate_record


def scope_issues(experience: dict, catalog: dict[str, set[str]]) -> dict[str, list[str]]:
    validate_record(experience)
    return {field: sorted(set(experience[field]) - catalog[field])
            for field in ('triggerTags', 'requiredEvidence', 'requiredCapabilities')
            if set(experience[field]) - catalog[field]}


def select_diagnostic_experiences(experiences: list[dict], observations: dict[str, set[str]],
                                  catalog: dict[str, set[str]]) -> list[dict]:
    """Preserve member order, selecting only executable conjunctions of facts."""
    for field in ('triggerTags', 'requiredEvidence', 'requiredCapabilities'):
        unknown = observations[field] - catalog[field]
        if unknown:
            raise ValueError(f'Unregistered observation in {field}: {sorted(unknown)}')
    selected = []
    for experience in experiences:
        issues = scope_issues(experience, catalog)
        if issues:
            raise ValueError(f'Experience scope has no observation producer: {issues}')
        if experience_matches(experience, observations['triggerTags'], observations['requiredEvidence'],
                              observations['requiredCapabilities']):
            selected.append(experience)
    return selected
