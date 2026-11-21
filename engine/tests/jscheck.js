// 提取 index.html 里的 <script> 内容做语法检查（node --check 等价）
const fs = require('fs');
const html = fs.readFileSync('D:/ai-apk/engine/app/src/main/assets/index.html', 'utf8');
const re = /<script[^>]*>([\s\S]*?)<\/script>/gi;
let m, i = 0, bad = 0;
while ((m = re.exec(html)) !== null) {
  i++;
  const code = m[1];
  if (!code.trim()) continue;
  try {
    new Function(code);   // 语法级检查（不执行顶层 DOM 逻辑…… new Function 会编译但不运行）
  } catch (e) {
    bad++;
    console.log('SCRIPT BLOCK ' + i + ' SYNTAX ERROR: ' + e.message);
    const lines = code.split('\n');
    console.log(lines.slice(0, 3).join('\n'));
  }
}
console.log('checked ' + i + ' script blocks, errors: ' + bad);
process.exit(bad ? 1 : 0);
