import type { Session } from './protocol';
// Se inyecta exclusivamente en el servidor elegido; las páginas externas no reciben la sesión.
export function bootstrap(session: Session | null, server: string): string {
  return `(function(){
    if(location.origin!==${JSON.stringify(server)})return;
    window.SM_EXPO=true;
    ${session ? `try { if(!localStorage.getItem('sm_token')&&!['/login.html','/register.html','/forgot-password.html','/reset-password.html','/verify-email.html'].includes(location.pathname)) {
      localStorage.setItem('sm_token',${JSON.stringify(session.token)});
      localStorage.setItem('sm_user',${JSON.stringify(JSON.stringify(session.user))});
    }} catch(e){}` : ''}
    const send=(type,body)=>window.ReactNativeWebView.postMessage(JSON.stringify({version:1,type,...body}));
    window.SpaceMusicApp={
      estado:function(){}, detener:function(){if(window.__smBridgeConnected)send('stop',{});},
      guardarArchivo:function(url){window.__smDownloadFile?.(url);},
      notificar:function(title,body,route){send('notify',{title:String(title).slice(0,200),body:String(body).slice(0,1000),route:route||'#/'});}
    };
    const service=new EventTarget(),worker=new EventTarget();
    window.__smWorkerPending=[];
    worker.postMessage=m=>window.__smWorkerPending.push(m);
    service.controller=worker; service.ready=Promise.resolve({active:worker});
    service.register=async()=>({active:worker,waiting:null,update:async()=>{},addEventListener:()=>{}});
    window.__smService=service;window.__smWorker=worker;
    try{Object.defineProperty(navigator,'serviceWorker',{configurable:true,value:service});}catch(e){}
    document.documentElement.style.colorScheme='dark';
  })();true;`;
}
