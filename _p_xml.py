import io
p='app/src/main/res/layout/activity_main.xml'
s=io.open(p,encoding='utf-8').read()
old='''<TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="运行日志" style="@style/SectionLabel" android:layout_marginBottom="8dp" />'''
assert old in s, 'anchor missing'
new='''<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical" android:layout_marginBottom="8dp">
                <TextView android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:text="运行日志" style="@style/SectionLabel" />
                <Button android:id="@+id/btn_token" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Token" android:textSize="10sp" android:textColor="@color/text_secondary" android:background="@drawable/glass_btn_secondary" android:tag="glass" android:textAllCaps="false" android:minWidth="0dp" android:minHeight="0dp" android:paddingLeft="10dp" android:paddingRight="10dp" android:paddingTop="4dp" android:paddingBottom="4dp" android:layout_marginEnd="6dp" />
                <Button android:id="@+id/btn_push" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="推送" android:textSize="10sp" android:textColor="@color/accent_cyan" android:background="@drawable/glass_btn_primary" android:tag="glass" android:textAllCaps="false" android:minWidth="0dp" android:minHeight="0dp" android:paddingLeft="10dp" android:paddingRight="10dp" android:paddingTop="4dp" android:paddingBottom="4dp" />
            </LinearLayout>'''
s=s.replace(old,new,1)
io.open(p,'w',encoding='utf-8').write(s)
print('XML_OK')
