package com.clarklevis.dsh.shared.facade

/** 选择页的两端共用排版；正文由各端已有的 Markdown AST 生成，不执行正文中的 HTML。 */
object MarkdownSelectionHtml {
    fun document(body: String, fontSize: Double, dark: Boolean, codeLabel: String): String = """
        <!doctype html><html data-theme="${if (dark) "dark" else "light"}"><head>
        <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
        <style>
        :root{--bg:#fff;--fg:#000;--code:#ebebed;--header:#efeff0;--alternate:#f8f8fa;--border:#dadadd;--accent:#007aff;--size:${fontSize.coerceIn(8.0, 72.0)}px;color-scheme:light}
        :root[data-theme=dark]{--bg:#000;--fg:#fff;--code:#2c2c2e;--header:#252527;--alternate:#111113;--border:#353538;--accent:#0a84ff;color-scheme:dark}
        *{box-sizing:border-box}html{background:var(--bg);color:var(--fg);-webkit-text-size-adjust:100%}
        body{margin:0;padding:16px 20px 24px;font:var(--size)/1.5 -apple-system,BlinkMacSystemFont,Roboto,"Noto Sans",sans-serif;overflow-wrap:anywhere;-webkit-user-select:text;user-select:text}
        p,blockquote,ul,ol,pre{margin:0 0 10px}h1,h2,h3,h4,h5,h6{margin:0 0 10px;line-height:1.25;font-weight:600}
        h1{font-size:1.65em}h2{font-size:1.35em}h3,h4,h5,h6{font-size:1.1em}ul,ol{padding-left:1.5em}li>p{margin-bottom:0}
        a{color:var(--accent);text-decoration:none}blockquote{padding-left:12px;border-left:3px solid var(--border)}
        code{font:0.86em/1.5 ui-monospace,SFMono-Regular,Consolas,monospace;background:var(--code);border-radius:4px;padding:0 3px}
        pre{position:relative;padding:34px 12px 12px;border-radius:9px;background:var(--code);white-space:pre-wrap;overflow-wrap:anywhere}
        pre:before{content:attr(data-language);position:absolute;top:10px;left:12px;font:11px/16px ui-monospace,monospace;color:#8e8e93;-webkit-user-select:none;user-select:none}
        pre code{font-size:.8em;padding:0;background:none;border-radius:0;white-space:pre-wrap}
        .table-scroll{width:100%;overflow-x:auto;margin:0 0 10px;border:1px solid var(--border);border-radius:12px;-webkit-overflow-scrolling:touch}
        table{table-layout:fixed;border-collapse:separate;border-spacing:0;font:inherit}
        th,td{vertical-align:top;padding:12px;border-right:1px solid var(--border);border-bottom:1px solid var(--border);overflow-wrap:anywhere;text-align:left}
        tr>:last-child{border-right:0}tbody tr:last-child td{border-bottom:0}
        th{font-weight:600;background:var(--header)}td{background:var(--bg)}tbody tr:nth-child(even) td{background:var(--alternate)}
        hr{border:0;border-top:1px solid var(--border);margin:10px 0}::selection{background:rgba(0,122,255,.22)}
        </style></head><body><main id="content">$body</main>
        <script>
        (()=>{
          const main=document.getElementById('content');
          main.querySelectorAll('a').forEach(a=>a.removeAttribute('href'));
          main.querySelectorAll('img').forEach(i=>i.replaceWith(document.createTextNode(i.alt)));
          main.querySelectorAll('th,td').forEach(c=>{const a=c.getAttribute('align');if(['left','center','right'].includes(a))c.style.textAlign=a;});
          main.querySelectorAll('pre').forEach(p=>{
            const c=p.querySelector('code'), match=c&&c.className.match(/language-([^\s]+)/);
            p.dataset.language=match?match[1].toUpperCase():${quote(codeLabel)};
          });
          const tables=Array.from(main.querySelectorAll('table')).map(table=>{
            const wrapper=document.createElement('div');wrapper.className='table-scroll';
            table.before(wrapper);wrapper.append(table);
            const columns=Math.max(...Array.from(table.rows).map(row=>row.cells.length),0);
            const group=document.createElement('colgroup');table.prepend(group);
            for(let i=0;i<columns;i++)group.append(document.createElement('col'));
            return {table,wrapper,group,columns};
          });
          function layout(){
            const canvas=document.createElement('canvas'), ctx=canvas.getContext('2d'), style=getComputedStyle(main);
            ctx.font=style.fontSize+' '+style.fontFamily;
            tables.forEach(({table,wrapper,group,columns})=>{
              let widths=Array.from({length:columns},(_,i)=>{
                let natural=0;Array.from(table.rows).forEach(row=>{
                  const cell=row.cells[i];if(cell)cell.textContent.split('\n').forEach(line=>natural=Math.max(natural,ctx.measureText(line).width));
                });return Math.min(260,Math.max(96,Math.ceil(natural)+24));
              });
              const total=widths.reduce((a,b)=>a+b,0), width=Math.max(total,wrapper.clientWidth-1);
              widths.forEach((w,i)=>group.children[i].style.width=(w*width/total)+'px');table.style.width=(width+1)+'px';
            });
          }
          window.dshSetAppearance=(size,dark)=>{document.documentElement.dataset.theme=dark?'dark':'light';document.documentElement.style.setProperty('--size',size+'px');layout();};
          window.dshSelectedText=()=>String(window.getSelection());
          window.dshSelectAll=()=>{const r=document.createRange();r.selectNodeContents(main);const s=window.getSelection();s.removeAllRanges();s.addRange(r);};
          window.dshSelectInitial=()=>{const first=main.querySelector('p,h1,h2,h3,th,td,li,pre');if(!first)return;const r=document.createRange();r.selectNodeContents(first);const s=window.getSelection();s.removeAllRanges();s.addRange(r);};
          document.addEventListener('copy',e=>{if(e.clipboardData){e.clipboardData.setData('text/plain',window.dshSelectedText());e.preventDefault();}});
          window.addEventListener('resize',layout);layout();window.dshReady=true;
        })();
        </script></body></html>
    """.trimIndent()

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("<", "\\u003c").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
