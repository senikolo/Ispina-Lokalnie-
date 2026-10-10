const {spawnSync}=require('node:child_process');
const path=require('node:path');
const suites=['regression','motion-regression','planner-r14-2','first-accept','safe-picker'];
for(const suite of suites){
  const result=spawnSync(process.execPath,[path.join(__dirname,suite+'.cjs')],{stdio:'inherit',env:process.env});
  if(result.error)throw result.error;
  if(result.status!==0)process.exit(result.status||1);
}
