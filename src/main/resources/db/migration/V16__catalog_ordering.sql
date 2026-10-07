-- V16: V12 inserted the investigation list and examination findings from a VALUES list without an
-- explicit order, so their ids (and therefore the display order) came out alphabetical. Make the
-- intended order explicit instead of depending on ids.

alter table investigation_catalog add column sort_order int not null default 0;

update investigation_catalog i
set sort_order = v.ord
from (values
    ('cbc',1),('fbs',2),('rbs',3),('hba1c',4),('lft',5),('rft',6),('lipid',7),('esr',8),('crp',9),
    ('urine-re',10),('urine-cs',11),('stool-re',12),('stool-cs',13),('blood-culture',14),
    ('dengue-ns1',15),('dengue-igm-igg',16),('typhoid',17),('xray-chest',18),('xray-abdomen',19),
    ('usg-abdomen',20),('usg-pelvis',21),('ct-scan',22),('mri',23),('ecg',24)
) as v(code, ord)
where i.code = v.code;

alter table examination_finding_catalog add column category_order int not null default 0;

update examination_finding_catalog
set category_order = case category
    when 'General' then 1
    when 'Chest / Respiratory' then 2
    when 'CVS / Cardiovascular' then 3
    when 'Abdomen' then 4
    when 'CNS / Neurological' then 5
    when 'ENT' then 6
    when 'Musculoskeletal' then 7
    when 'Skin' then 8
    else 99
end;
