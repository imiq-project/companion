"""Explicitly mounted experiment API; no database and no activation by default."""
from flask import request, jsonify
from .forecast_transport import read_forecast_json, ForecastBodyError

PREFIX = '/api/companion/longitudinal/backend-experiment'
EXTENSION = 'longitudinal_backend_experiment'


def register(app):
    def dispatch(action):
        runtime = app.extensions.get(EXTENSION)
        if runtime is None:return jsonify(error='backend_experiment_not_configured',production_authorized=False),503
        try:
            data = read_forecast_json(request)
            if not isinstance(data,dict):raise ValueError('Object body required')
            required = {'operations':set(),'profile':{'initial_passport'},'experience':{'root','record'},'work':{'retry_failed'},
                'prepare':{'root','target','mode'},'select':{'root','selection'},'status':{'root'},'reconstruct':{'root','expected_tip'}}
            if action != 'search' and set(data) != required[action]:raise ValueError('Unexpected experiment fields')
            if action == 'operations':
                from .backend_operations import summary
                result=summary(runtime)
            elif action == 'profile':result = runtime.register_profile(data['initial_passport'])
            elif action == 'experience':result = runtime.submit(data['root'],data['record'])
            elif action == 'work':
                if type(data['retry_failed']) is not bool:raise ValueError('Boolean retry flag required')
                result = runtime.run_next(retry_failed=data['retry_failed'])
            elif action == 'prepare':
                bundle = runtime.prepare(data['root'],data['target'],data['mode'])
                result = {k:bundle[k] for k in ('schema_version','source_passport_id','target','mode','bundle_digest','journeys','all_journeys_screened','evidence_scope')}
            elif action == 'select':result = runtime.select(data['root'],data['selection'])
            elif action == 'reconstruct':result = runtime.reconstruct(data['root'],data['expected_tip'])
            elif action == 'search':result = runtime.search(data)
            else:
                with runtime.archive() as archive:
                    runtime.profile(data['root'],archive.db)
                    result = {'root':data['root'],'tip':archive.state(data['root'])[0],
                        'jobs':[runtime.job(r[0],archive.db) for r in archive.db.execute('SELECT event FROM experiment_queue WHERE root=?',(data['root'],))],
                        'evidence_scope':runtime.scope,'experiment_enabled':runtime.enabled,'production_authorized':False}
                    result['preparation'] = [dict(version=v,target=t,mode=m,status=s,error=e) for v,t,m,s,e in archive.db.execute(
                        'SELECT version,target,mode,status,error FROM experiment_preparation WHERE root=?',(data['root'],))]
            return jsonify(result)
        except ForecastBodyError as exc:return jsonify(error=exc.error),exc.status
        except (ValueError,KeyError,TypeError,IndexError,AttributeError):
            return jsonify(error='invalid_backend_experiment_request'),422
        except RuntimeError:
            return jsonify(error='backend_experiment_job_failed_retryable'),503
    for action in ('profile','experience','work','prepare','select','search','reconstruct','status','operations'):
        app.add_url_rule(PREFIX+'/'+action,endpoint='longitudinal_backend_experiment_'+action,
            view_func=lambda action=action:dispatch(action),methods=['POST'])
