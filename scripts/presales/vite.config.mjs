import base from '../../mateclaw-ui/vite.config.ts';
export default {...base,root:new URL('../../mateclaw-ui',import.meta.url).pathname,server:{...base.server,host:'127.0.0.1',port:5198,strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:18118',changeOrigin:true},'/ws':{target:'ws://127.0.0.1:18118',ws:true}}}};
