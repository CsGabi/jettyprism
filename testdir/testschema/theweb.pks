create or replace package theweb as


procedure menu;

procedure test1;

procedure test2(a varchar2,b varchar2,c varchar2 default 'x');

procedure bightml(sizee varchar2) ;

procedure clobtest(a varchar2,b clob,c varchar2,d clob);

procedure gen_excel;

procedure show_info;

procedure gen_excel2;

procedure query_tables(pat varchar2);

procedure tables_demo;

procedure error;

end;
/