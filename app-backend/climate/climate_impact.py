"""Versioned attributional climate estimates. Never a HOTCO score or a causal saving."""
import copy
import hashlib
import json
import math
from datetime import datetime
from resources import CONTRACTS_DIR

FACTORS_PATH = CONTRACTS_DIR / 'climate-factors-v1.json'
FACTORS_BYTES = FACTORS_PATH.read_bytes()
FACTORS = json.loads(FACTORS_BYTES)
FACTOR_SHA = hashlib.sha256(FACTORS_BYTES).hexdigest()
METHOD = 'companion-climate-wtw-v1'
ALIASES = {'foot': 'walk', 'car_driver': 'car', 'pt_bus_tram': 'pt'}
FUELS = ('unknown', 'petrol', 'diesel', 'electric', 'hybrid_petrol')


def digest(value):
    raw = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
    return hashlib.sha256(raw.encode()).hexdigest()


def finite(value, low=0, high=1e9):
    return type(value) in (int, float) and math.isfinite(value) and low <= value <= high


def normalize_profile(profile):
    if profile is None:
        return None
    if not isinstance(profile, dict) or profile.get('schema_version') not in ('companion-vehicle-profile-v1','companion-vehicle-profile-v2'):
        raise ValueError('Unsupported vehicle emissions profile')
    allowed = {'schema_version', 'profile_id', 'revision', 'updated_at', 'make', 'model', 'year', 'variant',
               'fuel', 'fuel_blend', 'occupants', 'consumption_l_per_100km', 'electricity_kwh_per_100km',
               'homologated_co2_g_per_km', 'homologation_protocol', 'consumption_basis', 'bicycle_type'}
    if profile['schema_version']=='companion-vehicle-profile-v2':allowed.update(('catalogue','consumption_mode'))
    if set(profile) - allowed:
        raise ValueError('Unknown vehicle profile field')
    p = copy.deepcopy(profile)
    for key in ('profile_id', 'revision', 'updated_at'):
        if not isinstance(p.get(key), str) or not 1 <= len(p[key]) <= 160:
            raise ValueError('Vehicle profile identity is required')
    try:
        if datetime.fromisoformat(p['updated_at'].replace('Z','+00:00')).tzinfo is None:
            raise ValueError('Timezone is required')
    except ValueError as exc:
        raise ValueError('Vehicle profile updated_at must be an ISO timestamp with timezone') from exc
    for key in ('make', 'model', 'variant'):
        if not isinstance(p.get(key, ''), str) or len(p.get(key, '')) > 160:
            raise ValueError('Vehicle description exceeds bound')
    if p.get('year') is not None and (type(p['year']) is not int or not 1900 <= p['year'] <= 2100):
        raise ValueError('Invalid vehicle year')
    if p.get('fuel') not in FUELS or type(p.get('occupants')) is not int or not 1 <= p['occupants'] <= 9:
        raise ValueError('Choose fuel and 1–9 occupants including driver')
    for key, limit in (('consumption_l_per_100km', 100), ('electricity_kwh_per_100km', 200), ('homologated_co2_g_per_km', 2000)):
        minimum = 0 if p['fuel'] == 'hybrid_petrol' and key != 'homologated_co2_g_per_km' else 0.01
        if p.get(key) is not None and not finite(p[key], minimum, limit):
            raise ValueError('Invalid vehicle consumption or CO2 value')
    if p.get('fuel_blend', 'unknown') not in ('unknown', 'fossil', 'E5', 'E10', 'B7'):
        raise ValueError('Invalid fuel blend')
    if p.get('fuel_blend') in ('E5', 'E10') and p['fuel'] not in ('petrol', 'hybrid_petrol'):
        raise ValueError('E5/E10 requires petrol')
    if p.get('fuel_blend') == 'B7' and p['fuel'] != 'diesel':
        raise ValueError('B7 requires diesel')
    if p.get('homologation_protocol', 'unknown') not in ('unknown', 'WLTP', 'NEDC'):
        raise ValueError('Invalid homologation protocol')
    if p.get('consumption_basis', 'user_average') not in ('user_average', 'homologated'):
        raise ValueError('Invalid consumption provenance')
    if p.get('bicycle_type', 'assumed_conventional') not in ('assumed_conventional', 'conventional', 'electric'):
        raise ValueError('Invalid bicycle type')
    if p['fuel'] in ('unknown', 'electric') and p.get('consumption_l_per_100km') is not None:
        raise ValueError('Litres require a combustion fuel')
    if p['fuel'] in ('unknown', 'petrol', 'diesel') and p.get('electricity_kwh_per_100km') is not None:
        raise ValueError('Electric consumption requires electric or plug-in hybrid')
    if p['schema_version']=='companion-vehicle-profile-v2':
        mode=p.get('consumption_mode');catalogue=p.get('catalogue')
        if mode not in ('catalogue','personal','uba','legacy_reference'):raise ValueError('Invalid consumption mode')
        if catalogue is not None:
            if not isinstance(catalogue,dict) or catalogue.get('schema_version')!='companion-vehicle-catalogue-entry-v1':raise ValueError('Invalid catalogue reference')
            for key in ('entry_id','catalogue_version','source','source_url','license','make','model','variant'):
                if not isinstance(catalogue.get(key),str) or not 1<=len(catalogue[key])<=2000:raise ValueError('Missing catalogue provenance')
            values=catalogue.get('profile_values')
            if not isinstance(values,dict) or values.get('fuel')!=p['fuel']:raise ValueError('Catalogue fuel mismatch')
            normalize_profile({**values,'schema_version':'companion-vehicle-profile-v1','profile_id':'catalogue-reference','revision':'reference','updated_at':p['updated_at']})
            if mode=='catalogue':
                if p.get('consumption_basis')!='homologated':raise ValueError('Catalogue consumption provenance mismatch')
                for key in ('consumption_l_per_100km','electricity_kwh_per_100km','homologated_co2_g_per_km','homologation_protocol'):
                    if p.get(key)!=values.get(key):raise ValueError('Active values differ from catalogue reference')
        if mode=='catalogue' and catalogue is None:raise ValueError('Choose a catalogue vehicle')
        if mode=='legacy_reference' and catalogue is not None:raise ValueError('Legacy mode cannot claim a catalogue source')
        if mode=='uba' and (catalogue is not None or any(p.get(k) is not None for k in ('consumption_l_per_100km','electricity_kwh_per_100km','homologated_co2_g_per_km'))):raise ValueError('UBA mode cannot carry custom energy inputs')
        if mode=='personal':
            litres=p.get('consumption_l_per_100km');kwh=p.get('electricity_kwh_per_100km')
            if p['fuel'] in ('petrol','diesel') and litres is None or p['fuel']=='electric' and kwh is None or p['fuel']=='unknown':raise ValueError('Personal consumption is required')
            if p['fuel']=='hybrid_petrol' and (litres is None or kwh is None or litres+kwh<=0):raise ValueError('Both hybrid energy inputs are required')
            if p.get('consumption_basis')!='user_average' or p.get('homologated_co2_g_per_km') is not None:raise ValueError('Personal consumption provenance mismatch')
    return p


def factor(key):
    f = FACTORS['factors'][key]
    return f['kg_per_unit'], f['row_id']


def liquid_factor(fuel, blend):
    value, row = factor(fuel + '_litre')
    rows = [row]
    assumptions = ['UBA_liquid_fuel_reference']
    if blend in ('E5', 'E10', 'B7'):
        assumptions.append('declared_blend_metadata_only_not_recalculated')
    return value, rows, assumptions


def car_intensity(p):
    """kg CO2e per passenger-km, basis, row IDs and explicit scenario assumptions."""
    if p is None or p.get('schema_version')=='companion-vehicle-profile-v2' and p.get('consumption_mode')=='uba' and p.get('fuel')=='unknown' and not p.get('make') and not p.get('model'):
        value, row = factor('average_car_pkm')
        return value, 'national_passenger_average', [row], ['national_average_occupancy']
    fuel, n = p['fuel'], p['occupants']
    litres, electricity = p.get('consumption_l_per_100km'), p.get('electricity_kwh_per_100km')
    assumptions = ['consumption_is_an_estimate', 'cold_start_and_traffic_not_individually_modelled']
    if fuel == 'electric' and electricity is not None:
        value, row = factor('electricity_kwh')
        return electricity / 100 * value / n, 'declared_electric_consumption', [row], assumptions + ['consumption_must_include_charging_losses', 'German_average_electricity']
    if fuel == 'hybrid_petrol' and litres is not None and electricity is not None:
        a, rows, blend_assumptions = liquid_factor('petrol', p.get('fuel_blend'))
        b, br = factor('electricity_kwh')
        return (litres * a + electricity * b) / 100 / n, 'declared_dual_energy_consumption', rows + [br], assumptions + blend_assumptions + ['German_average_electricity', 'consumption_must_include_charging_losses']
    if fuel in ('petrol', 'diesel'):
        rows = []
        if litres is None and p.get('homologated_co2_g_per_km') is not None:
            direct, row = factor(fuel + '_tailpipe_co2_litre')
            litres = p['homologated_co2_g_per_km'] / 1000 / direct * 100
            rows.append(row)
            basis = 'homologation_inferred_fossil_consumption'
            assumptions += ['homologated_CO2_is_not_real_world_consumption', 'fossil_fuel_conversion_reference']
        else:
            basis = 'declared_' + p.get('consumption_basis', 'user_average')
        if litres is not None:
            # Apply the published UBA reference directly. E5/E10/B7 labels
            # are maximum retail blend classes, not measured biogenic shares.
            value, fuel_rows, fuel_assumptions = liquid_factor(fuel,p.get('fuel_blend'))
            rows.extend(fuel_rows);assumptions.extend(fuel_assumptions)
            return litres / 100 * value / n, basis, rows, assumptions
    key = {'petrol': 'petrol', 'diesel': 'diesel', 'electric': 'electric', 'hybrid_petrol': 'hybrid'}.get(fuel, 'average') + '_car_km'
    value, row = factor(key)
    return value / n, 'national_vehicle_average_with_declared_occupancy', [row], ['national_fleet_consumption', 'model_and_year_are_descriptive_not_a_consumption_lookup']


def segment(leg, index, p):
    mode = ALIASES.get(leg.get('mode'), leg.get('mode'))
    distance = leg.get('distance_meters')
    result = {'index': index, 'mode': mode, 'distance_meters': distance, 'distance_basis': leg.get('distance_basis', 'route_leg'), 'g_co2e': None, 'factor_rows': []}
    if not finite(distance):
        return {**result, 'distance_meters': None, 'reason': 'distance_unknown'}
    if mode == 'walk' or mode == 'bike' and (p or {}).get('bicycle_type') != 'electric':
        intensity, basis, rows, assumptions = 0, 'no_motor_energy', [], ['food_vehicle_manufacture_and_infrastructure_excluded']
        if mode == 'bike': assumptions += ['conventional_bicycle' if (p or {}).get('bicycle_type') == 'conventional' else 'conventional_bicycle_assumed']
    elif mode == 'bike':
        intensity, row = factor('ebike_km'); basis, rows, assumptions = 'national_ebike_average', [row], ['German_average_electricity']
    elif mode == 'car':
        intensity, basis, rows, assumptions = car_intensity(p)
    elif mode == 'pt':
        vehicle = (leg.get('transit') or {}).get('source_mode')
        # Generic OTP RAIL does not establish regional vs long-distance service.
        key = {'TRAM': 'tram_pkm', 'SUBWAY': 'tram_pkm', 'BUS': 'bus_pkm', 'REGIONAL_RAIL': 'regional_rail_pkm'}.get(vehicle)
        result['vehicle'] = vehicle
        if key is None:
            return {**result, 'reason': 'transit_vehicle_type_unknown_or_unsupported'}
        intensity, row = factor(key); basis, rows, assumptions = 'national_passenger_average', [row], ['national_average_occupancy', 'not_specific_to_MVB_vehicle_or_departure']
    else:
        return {**result, 'reason': 'unsupported_mode'}
    result={**result, 'g_co2e': round(distance * intensity, 6), 'basis': basis, 'kg_per_passenger_km': intensity,
            'factor_rows': rows, 'assumptions': assumptions}
    if mode=='car' and p is not None and basis!='national_passenger_average':
        litres=p.get('consumption_l_per_100km');kwh=p.get('electricity_kwh_per_100km')
        if litres is None and p['fuel'] in ('petrol','diesel') and p.get('homologated_co2_g_per_km') is not None:
            litres=p['homologated_co2_g_per_km']/1000/factor(p['fuel']+'_tailpipe_co2_litre')[0]*100
        # Default per-person comparisons remain unchanged. Vehicle totals and
        # energy cover only this driving leg, never pedestrian access distance.
        result.update(g_co2e_vehicle=round(distance*intensity*p['occupants'],6),
                      estimated_fuel_litres=round(distance*litres/100000,9) if litres is not None and basis.startswith(('declared_','homologation_')) else None,
                      estimated_electricity_kwh=round(distance*kwh/100000,9) if kwh is not None and basis.startswith('declared_') else None,
                      vehicle_occupants=p['occupants'])
    return result


def estimate(route, profile, search_id, at):
    legs = route.get('legs') or []
    mode = ALIASES.get(route.get('mode_key'), route.get('mode_key'))
    if not legs and mode in ('walk', 'bike', 'car'):
        legs = [{'mode': mode, 'distance_meters': (route.get('summary') or {}).get('distance_meters'), 'distance_basis': 'route_summary'}]
    parts = [segment(leg, i, profile) for i, leg in enumerate(legs)]
    complete = bool(parts) and all(s['g_co2e'] is not None for s in parts)
    summary = route.get('summary') or {}
    gap = summary.get('destination_access_time_unknown') is True or summary.get('origin_access_time_unknown') is True
    status = 'estimated' if complete and not gap else 'partial' if any(s['g_co2e'] is not None for s in parts) else 'unknown'
    if route.get('available') is not True: status = 'unavailable'
    known = round(sum(s['g_co2e'] for s in parts if s['g_co2e'] is not None), 6)
    value = {'schema_version': 'companion-route-climate-v1', 'method_version': METHOD,
             'factor_version': FACTORS['version'], 'factor_sha256': FACTOR_SHA,
             'source_url': FACTORS['source_url'], 'source_license': FACTORS['license'],
             'factor_region': FACTORS['geography'], 'boundary': FACTORS['boundary'], 'excluded': FACTORS['excluded'],
             'estimated_at': at, 'search_id': search_id, 'route_key': f"{route.get('rank')}-{route.get('mode_key')}",
             'profile_snapshot': copy.deepcopy(profile), 'profile_revision': profile['revision'] if profile else 'national-default-v1',
             'status': status, 'g_co2e': known if status == 'estimated' else None,
             'known_portion_g_co2e': known if any(s['g_co2e'] is not None for s in parts) else None, 'segments': parts,
             'uncertainty': {'type': 'not_quantified', 'statistical_confidence_interval': False},
             'coverage': 'mapped_portion_only' if gap else 'provided_itinerary',
             'scope_note': 'Estimated attribution per person; no measured emissions or causal avoided emissions.'}
    value['estimate_id'] = 'climate-' + digest(value)
    return value


def annotate(result, profile, at):
    p = normalize_profile(profile)
    routes = result['routing']['routes']
    estimates = [estimate(r, p, result['search_id'], at) for r in routes]
    references = {}
    for mode in ('car', 'pt'):
        eligible = [(r, e) for r, e in zip(routes, estimates) if ALIASES.get(r.get('mode_key'), r.get('mode_key')) == mode and e['status'] == 'estimated' and r.get('feasible') is not False]
        if mode == 'pt':
            eligible = [pair for pair in eligible if finite((pair[0].get('transit') or {}).get('end_time_ms'), 0, 1e16)]
            eligible.sort(key=lambda pair: (pair[0].get('transit') or {}).get('end_time_ms', float('inf')))
        references[mode] = eligible[0][1] if eligible else None
    for route, value in zip(routes, estimates):
        comparisons = {}
        for mode, reference in references.items():
            valid = reference is not None and value['status'] == 'estimated'
            comparisons[mode] = {'status': 'estimated_difference' if valid else 'unknown',
                                 'difference_g_co2e': round(reference['g_co2e'] - value['g_co2e'], 6) if valid else None,
                                 'reference': copy.deepcopy(reference) if reference is not None else None,
                                 'reference_policy': 'first_available_car_route' if mode == 'car' else 'earliest_arriving_returned_transit_route',
                                 'meaning': 'reference_minus_selected_not_causal_savings'}
        route['climate_estimate'] = {**value, 'comparisons': comparisons}
    result['climate_method'] = {'version': METHOD, 'factor_version': FACTORS['version'], 'factor_sha256': FACTOR_SHA,
                                'profile_revision': p['revision'] if p else 'national-default-v1', 'affects_hotco': False}


def expected_outcome(estimate_value, completion, actual_modes):
    """Freeze what can be inferred from the report; changed distance stays unknown."""
    status = 'not_taken' if completion == 'not_taken' else 'estimated_confirmed_itinerary' if completion == 'as_planned' else 'unknown_changed_itinerary'
    known = completion == 'as_planned' and estimate_value.get('status') == 'estimated'
    if completion == 'as_planned' and not known: status = 'unknown_incomplete_itinerary'
    return {'schema_version': 'companion-journey-climate-v1', 'method_version': METHOD,
            'status': status, 'plan_snapshot': copy.deepcopy(estimate_value), 'reported_modes': list(actual_modes),
            'g_co2e': estimate_value.get('g_co2e') if known else 0 if completion == 'not_taken' else None,
            'comparisons': copy.deepcopy(estimate_value.get('comparisons')) if known else None,
            'basis': 'user_confirmed_planned_itinerary_not_GPS_measurement' if known else 'no_completed_itinerary_measurement'}


def validate_snapshot(value):
    """Check frozen arithmetic without substituting today's factors into history."""
    if not isinstance(value, dict) or value.get('schema_version') != 'companion-route-climate-v1' or value.get('method_version') != METHOD:
        raise ValueError('Invalid climate snapshot version')
    normalize_profile(value.get('profile_snapshot'))
    parts=value.get('segments')
    if not isinstance(parts,list):raise ValueError('Missing climate segments')
    known=[]
    for s in parts:
        g=s.get('g_co2e')
        if g is None:continue
        distance=s.get('distance_meters');intensity=s.get('kg_per_passenger_km')
        if not finite(g) or not finite(distance) or not finite(intensity) or abs(g-distance*intensity)>0.00001:
            raise ValueError('Climate segment arithmetic mismatch')
        known.append(g)
        if s.get('g_co2e_vehicle') is not None:
            n=s.get('vehicle_occupants')
            if s.get('mode')!='car' or type(n) is not int or not 1<=n<=9 or (value.get('profile_snapshot') or {}).get('occupants')!=n or not finite(s['g_co2e_vehicle']) or abs(s['g_co2e_vehicle']-g*n)>0.00002:raise ValueError('Vehicle climate total mismatch')
            for key in ('estimated_fuel_litres','estimated_electricity_kwh'):
                if s.get(key) is not None and not finite(s[key]):raise ValueError('Invalid driving energy amount')
    g=value.get('g_co2e')
    if value.get('status')=='estimated':
        if not parts or len(known)!=len(parts) or not finite(g) or abs(g-sum(known))>0.00001:
            raise ValueError('Climate total mismatch')
    elif g is not None:raise ValueError('Incomplete climate snapshot has a total')
    for c in value.get('comparisons',{}).values():
        ref=c.get('reference')
        if ref is not None:
            validate_snapshot(ref)
            if any(value.get(k)!=ref.get(k) for k in ('search_id','profile_revision','profile_snapshot','factor_version','factor_sha256','boundary')):
                raise ValueError('Climate reference provenance mismatch')
        delta=c.get('difference_g_co2e')
        if c.get('status')=='estimated_difference':
            if not ref or ref.get('status')!='estimated' or value.get('status')!='estimated' or not finite(delta,-1e9) or abs(delta-(ref['g_co2e']-g))>0.00001:
                raise ValueError('Climate comparison arithmetic mismatch')
        elif delta is not None:raise ValueError('Unknown comparison has a numerical claim')


def validate_outcome(record, journey):
    planned = journey['plan']['route'].get('climate_estimate')
    if planned is not None:
        validate_snapshot(planned)
        if planned.get('profile_snapshot') != record['request'].get('climate_profile'):
            raise ValueError('Climate profile differs from frozen request')
    e = journey.get('experience')
    if not e: return
    if planned is None and 'climate_outcome' not in e: return
    if 'climate_outcome' not in e: raise ValueError('Climate outcome is required for a climate-enabled plan')
    if not isinstance(planned, dict): raise ValueError('Climate outcome lacks its original plan')
    expected = expected_outcome(planned, e['completion'], e['actual_modes'])
    if e['climate_outcome'] != expected: raise ValueError('Climate outcome differs from frozen plan or completion report')
