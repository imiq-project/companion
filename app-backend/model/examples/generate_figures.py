"""Reproduce model documentation figures using synthetic declarations only.

Run from the repository root: python app-backend/model/examples/generate_figures.py
Matplotlib is a documentation dependency, not a backend runtime requirement.
"""
from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap
import numpy as np

BACKEND=Path(__file__).resolve().parents[2]
ROOT=BACKEND.parent
sys.path.insert(0,str(BACKEND))
from model.hotco_signed_v1 import SignedHOTCO, MODEL_VERSION, MODAL_PATTERN
from model.input_mapping_v4_3 import parse_participant_input, INPUT_SCHEMA_VERSION, NEEDS, MODES
from model.action_readout import signed_action_readout

HERE=Path(__file__).resolve().parent
OUTPUT=HERE/'figures'
OUTPUT.mkdir(exist_ok=True)
PURPLE='#623CEA';BURGUNDY='#7A003F';BLUE='#A5C4D4';LIGHT='#E4EBF2'
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'axes.spines.top':False,
    'axes.spines.right':False,'axes.labelcolor':'#000000','text.color':'#000000',
    'savefig.facecolor':'#FFFFFF','figure.facecolor':'#FFFFFF','svg.fonttype':'none'})
mode_names=['Car','Bicycle','Public transport','Walking']
need_names=['Environment','Activity','Cost','Speed','Comfort']
colors=[BURGUNDY,PURPLE,PURPLE,BURGUNDY]
styles=['-','-','--','--']

payload={'schema_version':INPUT_SCHEMA_VERSION,'agent_id':'synthetic-model-documentation',
    'responses':{'needs':dict(zip(NEEDS,[6,5,4,3,6])),
    'beliefs':{mode:dict(zip(NEEDS,row)) for mode,row in zip(MODES,
        [[2,1,2,7,6],[7,7,6,4,3],[6,3,5,4,5],[7,7,7,1,4]])},
    'valences':dict(zip(MODES,[-1,2,1,1])),
    'availability':dict.fromkeys(MODES,True)}}
participant=parse_participant_input(payload)
engine=SignedHOTCO()
result=engine.simulate(participant)
states=result.trajectory[0];terminal=result.final_state[0];actions=terminal[5:9]
readout=signed_action_readout(actions,participant.availability)
excluded_index=int(np.argmax(actions))
availability=list(participant.availability);availability[excluded_index]=0
variant_payload=json.loads(json.dumps(payload))
variant_payload['responses']['availability']=dict(zip(MODES,map(bool,availability)))
variant=parse_participant_input(variant_payload)
masked=engine.simulate(variant)
np.testing.assert_array_equal(result.trajectory,masked.trajectory)
masked_readout=signed_action_readout(actions,availability)
assert np.max(np.abs(states))<=1+1e-10
np.testing.assert_array_equal(result.topology[0],result.topology[0].T)

def save(fig,name):
    fig.savefig(OUTPUT/(name+'.png'),dpi=180,bbox_inches='tight')
    fig.savefig(OUTPUT/(name+'.svg'),bbox_inches='tight')
    plt.close(fig)

fig,axes=plt.subplots(1,3,figsize=(12,3.8),layout='constrained')
ratings=np.arange(1,8)
axes[0].plot(ratings,(ratings-1)/6,'o-',color=PURPLE)
axes[0].set(title='Goal importance',xlabel='Declared rating (1–7)',ylabel='Initial goal activation')
axes[1].plot(ratings,(ratings-4)/3,'o-',color=PURPLE)
axes[1].set(title='Mode–goal belief',xlabel='Declared rating (1–7)',ylabel='Signed connection weight')
affect=np.arange(-3,4)
axes[2].plot(affect,affect/3,'o-',color=BURGUNDY)
axes[2].set(title='Mode valence',xlabel='Declared affect (−3 to +3)',ylabel='Initial valence activation')
for ax in axes:ax.axhline(0,color='#000000',lw=.6);ax.grid(alpha=.13,color='#000000')
fig.suptitle('Measurement bridge · explicit declarations, no imputation',fontweight='bold',fontsize=13)
save(fig,'01-measurement-bridge')

labels=['N:'+x for x in ['env','activity','cost','speed','comfort']]+['A:'+m for m in MODES]+['V:'+m for m in MODES]
fig,ax=plt.subplots(figsize=(9,8),layout='constrained')
cmap=LinearSegmentedColormap.from_list('IMIQ_signed',[BURGUNDY,'#FFFFFF',PURPLE])
im=ax.imshow(result.topology[0],cmap=cmap,vmin=-1,vmax=1)
ax.set_xticks(range(13),labels,rotation=55,ha='right');ax.set_yticks(range(13),labels)
for boundary in [4.5,8.5]:
    ax.axhline(boundary,color='#000000',lw=1);ax.axvline(boundary,color='#000000',lw=1)
ax.set(xlabel='Source state (column)',ylabel='Receiving state (row)',
    title='Synthetic 13-state topology · W is symmetric\nN: goals · A: actions · V: valences')
fig.colorbar(im,ax=ax,shrink=.75,label='Signed weight · burgundy negative / purple positive')
save(fig,'02-network-topology')

fig,axes=plt.subplots(1,3,figsize=(15,4.3),layout='constrained')
for i,name in enumerate(mode_names):
    for ax in axes[:2]:ax.plot(result.times,states[:,5+i],color=colors[i],ls=styles[i],label=name,lw=1.8)
axes[0].set(xlim=(0,20),title='Early trajectory (zoom)',xlabel='Model time — not seconds')
axes[1].set(xlim=(0,result.times[-1]),title='Full integrated trajectory',xlabel='Model time — not seconds')
for ax in axes[:2]:
    ax.set_ylim(-1.05,1.05);ax.axhline(0,color='#000000',lw=.6)
    ax.set_ylabel('Signed action activation');ax.grid(alpha=.13,color='#000000')
axes[0].legend(fontsize=8,loc='best')
bars=axes[2].bar(range(4),actions,color=[BLUE if i==excluded_index else PURPLE for i in range(4)],edgecolor=BURGUNDY)
bars[excluded_index].set_hatch('///')
axes[2].axhline(0,color='#000000',lw=.7)
axes[2].set_xticks(range(4),['Car','Bike','PT','Walk'])
axes[2].set(ylim=(-1.05,1.05),ylabel='Same terminal activation',title='Availability filters readout only')
axes[2].set_xlabel('Hatched: excluded at readout; trajectory unchanged',fontsize=8)
fig.suptitle('Synthetic example · production solver and parameters · not observed travel behavior',fontweight='bold',fontsize=13)
save(fig,'03-trajectories-and-readout')

w=result.topology[0]
cognitive=w[5:9,:5]@terminal[:5]
modal=w[5:9,5:9]@terminal[5:9]
affective=w[5:9,9:]@terminal[9:]
net=(w@terminal)[5:9]
np.testing.assert_allclose(cognitive+modal+affective,net,rtol=1e-12,atol=1e-12)
fig,ax=plt.subplots(figsize=(10,4.5),layout='constrained')
x=np.arange(4);width=.23
for offset,values,color,label in [(-width,cognitive,PURPLE,'Goal–belief input'),(0,modal,BLUE,'Modal input'),(width,affective,BURGUNDY,'Valence input')]:
    ax.bar(x+offset,values,width,color=color,label=label,edgecolor='#000000',linewidth=.4)
ax.plot(x,net,'D',color='#000000',label='Total net input',markersize=5)
ax.axhline(0,color='#000000',lw=.7);ax.set_xticks(x,mode_names)
ax.set(title='Terminal net-input decomposition · mechanistic, not causal attribution',
       ylabel='Signed net input (not a probability or a bounded activation)')
ax.legend(ncol=2,fontsize=9);ax.grid(axis='y',alpha=.13,color='#000000')
save(fig,'04-net-input-decomposition')

(HERE/'synthetic-input.json').write_text(json.dumps(payload,indent=2)+'\n',encoding='utf-8')
report={'scope':'Synthetic documentation only; no participant data, providers, GPS or learning.',
    'model_version':MODEL_VERSION,'parameters':asdict(engine.parameters),
    'source_sha256':{str(p.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest()
        for p in [BACKEND/'model/hotco_signed_v1.py',BACKEND/'model/input_mapping_v4_3.py',
                  BACKEND/'model/action_readout.py',ROOT/'contracts/hotco-signed-model-v1.json',Path(__file__).resolve()]},
    'initial_state':result.trajectory[0,0].tolist(),'terminal_state':terminal.tolist(),
    'readout_all_available':readout,'excluded_mode_for_demonstration':MODES[excluded_index],
    'readout_with_exclusion':masked_readout,'availability_trajectories_exactly_equal':True,
    'numerical_audit':result.numerical[0],
    'terminal_net_inputs':{'cognitive':cognitive.tolist(),'modal':modal.tolist(),'affective':affective.tolist(),'total':net.tolist()}}
(HERE/'example-results.json').write_text(json.dumps(report,indent=2,allow_nan=False)+'\n',encoding='utf-8')
print(json.dumps({'figures':4,'solver':result.numerical[0]['solver'],'actual_horizon':float(result.times[-1]),
    'readout':readout,'masked_readout':masked_readout,'verified_stable_equilibrium':result.numerical[0]['verified_stable_equilibrium'],
    'availability_trajectories_exactly_equal':True}))
