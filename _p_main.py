import io
p='app/src/main/java/com/tencent/tim/MainActivity.java'
s=io.open(p,encoding='utf-8').read()
a='String newLog = "[" + time + "] " + log'
b='String newLog = "[" + time + "] " + PushManager.scrub(log)'
assert a in s
s=s.replace(a,b,1)
mk='        tvLog = findViewById(R.id.tv_log);'
assert mk in s
ins=mk+'''
        android.widget.Button pushBtn = findViewById(R.id.btn_push);
        if (pushBtn != null) pushBtn.setOnClickListener(v -> showPushDialog());
        android.widget.Button tokenBtn = findViewById(R.id.btn_token);
        if (tokenBtn != null) tokenBtn.setOnClickListener(v -> showTokenDialog());'''
s=s.replace(mk,ins,1)
anc='    private void showCopyTokenMenu() {'
assert anc in s
methods='''    private void showPushDialog() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("ghp_xxx (GitHub Token)");
        String cur = PushManager.token(this);
        if (!cur.isEmpty()) {
            et.setText(cur);
            et.setSelection(cur.length());
        }
        et.setTextColor(0xFFF5F7FA);
        et.setHintTextColor(0xFF5C6B84);
        new AlertDialog.Builder(this)
                .setTitle("推送到 GitHub")
                .setMessage("仓库: " + PushManager.repo(this) + "  分支: " + PushManager.branch(this))
                .setView(et)
                .setPositiveButton("开始推送", (d, w) -> {
                    String tk = et.getText().toString().trim();
                    if (tk.isEmpty()) {
                        addLog("[推送] Token 为空，已取消");
                        return;
                    }
                    PushManager.setToken(this, tk);
                    addLog("[推送] Token 已保存: " + PushManager.mask(tk));
                    new Thread(() -> PushManager.pushAll(MainActivity.this, MainActivity.this::addLog)).start();
                })
                .setNeutralButton("清除 Token", (d, w) -> {
                    PushManager.setToken(this, "");
                    addLog("[推送] Token 已清除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTokenDialog() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(PushManager.token(this));
        et.setTextColor(0xFFF5F7FA);
        new AlertDialog.Builder(this)
                .setTitle("GitHub Token")
                .setMessage("当前: " + PushManager.mask(PushManager.token(this)))
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    PushManager.setToken(this, et.getText().toString());
                    addLog("[推送] Token 已保存: " + PushManager.mask(PushManager.token(this)));
                })
                .setNegativeButton("取消", null)
                .show();
    }

'''
s=s.replace(anc,methods+anc,1)
io.open(p,'w',encoding='utf-8').write(s)
print('MAIN_OK')
