"""Companion backend operation and stateless diagnostics. See README.md."""
import argparse,json,os,subprocess,sys,sqlite3
from pathlib import Path
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'app-backend'))


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    commands=parser.add_subparsers(dest='command',required=True)
    config=commands.add_parser('config');config.add_argument('--json',action='store_true')
    ops=commands.add_parser('ops');ops.add_argument('action',choices=('plan','status','start','restart','stop'));ops.add_argument('--service',choices=('all','backend','otp','realtime'),default='all')
    check=commands.add_parser('check')
    for flag in ('services','providers','validation','include-weather','synthetic-route'):check.add_argument('--'+flag,action='store_true')
    check.add_argument('--timeout',type=float,default=15);check.add_argument('--base-url');check.add_argument('--otp-url');check.add_argument('--realtime-url');check.add_argument('--output',type=Path)
    data=commands.add_parser('data');data.add_argument('dataset',choices=('transit','vehicles','climate','journeys'));data.add_argument('action',choices=('verify','bootstrap','stage','activate','rollback','watch','sync','status'),default='verify',nargs='?');data.add_argument('--release',type=Path);data.add_argument('--osm',type=Path,help='Local Magdeburg OSM PBF for initial transit bootstrap')
    serve=commands.add_parser('serve');serve.add_argument('--production',action='store_true')
    foreground=commands.add_parser('run-service');foreground.add_argument('role',choices=('otp','realtime'))
    args=parser.parse_args(argv)
    from infrastructure.service_config import settings
    from operations import runtime
    try:
        config=settings()
        if args.command=='config':
            print(json.dumps({'backend_port':config.backend_port,'otp_port':config.otp_port,'realtime_port':config.realtime_port,
                'state_directory':str(config.state),'java':config.java,'otp_jar':str(config.otp_jar),
                'capacity_per_worker':config.max_inflight,'max_body_bytes':config.max_body_bytes,
                'api_auth_configured':bool(config.api_token),'bind_host':config.host},indent=2));return 0
        if args.command=='ops':
            if args.action=='status':print(json.dumps(runtime.status(config),indent=2));return 0
            roles=('realtime','otp','backend') if args.service=='all' else (args.service,)
            release=runtime.active_release(config) if args.service!='backend' else None
            if args.action=='plan':
                if release is not None:runtime.verify_release(release,config)
                print(json.dumps({'release':str(release) if release else None,'commands':{r:runtime.command(r,release,config) for r in roles}},indent=2));return 0
            result=runtime.stop(roles,config=config) if args.action=='stop' else runtime.start(roles,config=config,restart=args.action=='restart')
            print(json.dumps(result,indent=2));return 0
        if args.command=='check':
            if not 0<args.timeout<=60:parser.error('--timeout must be >0 and <=60')
            from operations import checks
            only_route=args.synthetic_route and not (args.services or args.providers or args.validation or args.include_weather)
            result=checks.synthetic_route(args.base_url) if only_route else checks.run(providers=args.providers,validation=args.validation,include_weather=args.include_weather,timeout=args.timeout,base_url=args.base_url,otp_url=args.otp_url,realtime_url=args.realtime_url)
            if args.synthetic_route and not only_route:
                try:result['synthetic_route']=checks.synthetic_route(args.base_url)
                except (OSError,ValueError,KeyError):
                    result['synthetic_route']={'status':'failed','reason':'synthetic_route_unavailable'}
                    result['exit_code']=result['exit_code'] or 2;result['overall']='degraded'
            if args.output:runtime.atomic_json(args.output,result)
            print(json.dumps(result,indent=2));return result.get('exit_code',0)
        if args.command=='data':
            if args.osm is not None and (args.dataset!='transit' or args.action!='bootstrap'):parser.error('--osm is only supported by data transit bootstrap')
            if args.dataset=='journeys':
                if args.action not in ('verify','status'):parser.error('Journey storage supports verify/status only')
                from journeys.archive import storage_status
                result=storage_status();print(json.dumps(result,indent=2))
                return 1 if result.get('integrity','ok')!='ok' else 0
            if args.dataset=='transit':
                from operations.transit import stage,deploy,watch,sync,resolve_release,bootstrap
                if args.action=='bootstrap':bootstrap(args.osm);return 0
                if args.action=='sync':
                    result=sync();print(json.dumps(result,indent=2));return 0
                if args.action=='status':
                    path=config.state/'transit-sync.json'
                    print(path.read_text() if path.exists() else json.dumps({'status':'never_checked'}));return 0
                if args.action=='verify':runtime.verify_release(runtime.active_release(config),config);print('Transit release verified');return 0
                if args.action=='stage':stage();return 0
                if args.action=='activate':
                    release=args.release or Path(json.loads((config.state/'transit-staged.json').read_text())['release'])
                    deploy(release);return 0
                if args.action=='rollback':
                    previous=json.loads(config.active_pointer.read_text()).get('previous_release')
                    if not previous:raise ValueError('No previous release is recorded')
                    runtime.activate(resolve_release(previous,config),config);return 0
                if args.action=='watch':watch();return 0
            if args.action!='verify':parser.error('Public vehicle/climate catalogues are verified here; rebuild them in the development sources')
            from climate.climate_impact import FACTORS
            paths=[ROOT/'contracts/climate-factors-v1.json'] if args.dataset=='climate' else [ROOT/'contracts/vehicle-catalogue-v1.json.gz',ROOT/'contracts/vehicle-names-gb-v1.json.gz']
            print(json.dumps({'dataset':args.dataset,'files':{p.name:runtime.sha(p) for p in paths},'factor_version':FACTORS['version']},indent=2));return 0
        if args.command=='serve':
            if args.production:
                if sys.platform=='win32':raise ValueError('Gunicorn requires Linux/WSL; use local mode on Windows')
                return subprocess.run([sys.executable,'-m','gunicorn','--chdir',str(runtime.BACKEND),'--config',str(ROOT/'deploy/gunicorn.conf.py'),'application:create_app()'],cwd=ROOT).returncode
            from application import create_app
            create_app().run(host=config.host,port=config.backend_port,debug=False);return 0
        if args.command=='run-service':
            release=runtime.active_release(config);runtime.verify_release(release,config)
            command=runtime.command(args.role,release,config)
            os.execv(command[0],command)
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError,sqlite3.Error,KeyError) as exc:
        print(json.dumps({'status':'error','reason':str(exc)},ensure_ascii=False),file=sys.stderr);return 1


if __name__=='__main__':raise SystemExit(main())
