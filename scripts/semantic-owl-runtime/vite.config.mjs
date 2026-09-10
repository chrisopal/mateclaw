import base from '../../mateclaw-ui/vite.config.ts';
export default {...base, root:new URL('../../mateclaw-ui',import.meta.url).pathname,server:{...base.server,port:5189,host:'127.0.0.1',strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:18109',changeOrigin:true},'/ws':{target:'ws://127.0.0.1:18109',ws:true}}}};
