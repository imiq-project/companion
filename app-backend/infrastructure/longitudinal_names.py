"""Read historical identifier spellings without altering signed source documents."""

VERSION_FIELDS = frozenset({'schema_version', 'model_version', 'weather_model_version',
    'digest_encoding', 'version', 'projection_version'})


def canonical_identifier(value):
    if not isinstance(value, str):
        return value
    if value.startswith('d-day-longitudinal-'):
        return 'longitudinal-evidence-' + value[len('d-day-longitudinal-'):]
    if value.startswith('d-day-'):
        return 'longitudinal-' + value[len('d-day-'):]
    return value


def contract_view(value, *, identifier=True):
    """Comparison-only view; identities/digests always use the original bytes.

    Only version fields are normalized inside documents. Subject, event, route,
    snapshot IDs and digest values remain exact even if they contain old words.
    """
    if isinstance(value, dict):
        return {key: contract_view(item, identifier=key in VERSION_FIELDS)
                for key, item in value.items()}
    if isinstance(value, list):
        return [contract_view(item, identifier=identifier) for item in value]
    if isinstance(value, tuple):
        return tuple(contract_view(item, identifier=identifier) for item in value)
    return canonical_identifier(value) if identifier else value


def canonical_api_path(path):
    return path.replace('/api/companion/d-day/', '/api/companion/longitudinal/', 1)


def register_legacy_urls(app):
    """Old URL spellings delegate to the same handlers and admission controls."""
    for rule in list(app.url_map.iter_rules()):
        if '/api/companion/longitudinal/' not in rule.rule:
            continue
        old_path = rule.rule.replace('/api/companion/longitudinal/', '/api/companion/d-day/', 1)
        endpoint = rule.endpoint.replace('longitudinal_', 'd_day_').replace('_longitudinal', '_d_day')
        app.add_url_rule(old_path, endpoint=endpoint, view_func=app.view_functions[rule.endpoint],
                         methods=sorted(rule.methods - {'HEAD', 'OPTIONS'}))


def legacy_contract_name(name):
    return name.replace('longitudinal-evidence-', 'd-day-longitudinal-', 1) if name.startswith('longitudinal-evidence-') else name.replace('longitudinal-', 'd-day-', 1)
