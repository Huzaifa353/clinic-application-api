-- V12: default clinic + the reference lists the app ships with (taken from the frontend's CatalogService
-- and ServiceCatalogService). Users are NOT created here: password hashes are produced by the application.
-- Demo patients / appointments / medicines are separate dev-only seed data, not part of the schema.

do $$
declare
    cid bigint;
begin
    insert into clinic (name) values ('Clinstra Family Clinic') returning id into cid;

    insert into clinic_settings (clinic_id, print_clinic_name) values (cid, 'Clinstra Family Clinic');

    insert into symptom_catalog (clinic_id, name)
    select cid, n from unnest(array[
        'Fever','Cough','Cold','Sore Throat','Headache','Body Ache','Vomiting','Nausea','Diarrhea',
        'Abdominal Pain','Fatigue','Dizziness','Chest Pain','Shortness of Breath','Loss of Appetite'
    ]) as n;

    insert into diagnosis_catalog (clinic_id, name)
    select cid, n from unnest(array[
        'Viral Fever','Gastroenteritis','Hypertension','Diabetes Mellitus','Upper Respiratory Tract Infection',
        'Urinary Tract Infection','Migraine','Allergic Rhinitis','Typhoid','Gastric Ulcer','Bronchitis','Anemia'
    ]) as n;

    insert into investigation_catalog (clinic_id, code, name, full_name, grp)
    select cid, v.code, v.name, v.full_name, v.grp
    from (values
        ('cbc','CBC','Complete Blood Count','Laboratory'),
        ('fbs','FBS','Fasting Blood Sugar','Laboratory'),
        ('rbs','RBS','Random Blood Sugar','Laboratory'),
        ('hba1c','HbA1c','Glycated Hemoglobin','Laboratory'),
        ('lft','LFT','Liver Function Test','Laboratory'),
        ('rft','RFT','Renal Function Test','Laboratory'),
        ('lipid','Lipid Profile','Lipid Profile','Laboratory'),
        ('esr','ESR','Erythrocyte Sedimentation Rate','Laboratory'),
        ('crp','CRP','C-Reactive Protein','Laboratory'),
        ('urine-re','Urine R/E','Urine Routine Examination','Laboratory'),
        ('urine-cs','Urine C/S','Urine Culture & Sensitivity','Laboratory'),
        ('stool-re','Stool R/E','Stool Routine Examination','Laboratory'),
        ('stool-cs','Stool C/S','Stool Culture & Sensitivity','Laboratory'),
        ('blood-culture','Blood Culture','Blood Culture','Laboratory'),
        ('dengue-ns1','Dengue NS1','Dengue NS1 Antigen','Laboratory'),
        ('dengue-igm-igg','Dengue IgM/IgG','Dengue IgM/IgG Antibody','Laboratory'),
        ('typhoid','Typhoid Test','Typhoid Test (Widal/Typhidot)','Laboratory'),
        ('xray-chest','X-Ray Chest','X-Ray Chest','Imaging'),
        ('xray-abdomen','X-Ray Abdomen','X-Ray Abdomen','Imaging'),
        ('usg-abdomen','Ultrasound Abdomen','Ultrasound Abdomen','Imaging'),
        ('usg-pelvis','Ultrasound Pelvis','Ultrasound Pelvis','Imaging'),
        ('ct-scan','CT Scan','CT Scan','Imaging'),
        ('mri','MRI','Magnetic Resonance Imaging','Imaging'),
        ('ecg','ECG','Electrocardiogram','Other')
    ) as v(code, name, full_name, grp);

    insert into examination_finding_catalog (clinic_id, category, finding, sort_order)
    select cid, v.category, v.finding, row_number() over (partition by v.category order by v.ord)
    from (values
        ('General',1,'Normal'),('General',2,'Conscious'),('General',3,'Alert'),('General',4,'Oriented'),
        ('General',5,'Pallor'),('General',6,'Jaundice'),('General',7,'Cyanosis'),('General',8,'Edema'),
        ('General',9,'Dehydration'),('General',10,'Feverish'),
        ('Chest / Respiratory',1,'Clear'),('Chest / Respiratory',2,'Wheeze'),('Chest / Respiratory',3,'Crackles'),
        ('Chest / Respiratory',4,'Reduced Air Entry'),('Chest / Respiratory',5,'Rhonchi'),('Chest / Respiratory',6,'Crepitations'),
        ('CVS / Cardiovascular',1,'S1/S2 Normal'),('CVS / Cardiovascular',2,'Regular Rhythm'),
        ('CVS / Cardiovascular',3,'Irregular Rhythm'),('CVS / Cardiovascular',4,'Murmur'),
        ('CVS / Cardiovascular',5,'Tachycardia'),('CVS / Cardiovascular',6,'Bradycardia'),
        ('Abdomen',1,'Soft'),('Abdomen',2,'Non-tender'),('Abdomen',3,'Tender'),('Abdomen',4,'Distended'),
        ('Abdomen',5,'Guarding'),('Abdomen',6,'Rigidity'),('Abdomen',7,'Hepatomegaly'),
        ('CNS / Neurological',1,'Conscious'),('CNS / Neurological',2,'Alert'),('CNS / Neurological',3,'Oriented'),
        ('CNS / Neurological',4,'Normal Neurological Exam'),('CNS / Neurological',5,'Weakness'),
        ('CNS / Neurological',6,'Numbness'),('CNS / Neurological',7,'Tremor'),
        ('ENT',1,'Normal'),('ENT',2,'Throat Congested'),('ENT',3,'Tonsillar Enlargement'),('ENT',4,'Tonsillar Exudate'),
        ('ENT',5,'Pharyngeal Erythema'),('ENT',6,'Nasal Congestion'),('ENT',7,'Nasal Discharge'),('ENT',8,'Ear Pain'),
        ('Musculoskeletal',1,'Normal ROM'),('Musculoskeletal',2,'Reduced ROM'),('Musculoskeletal',3,'Joint Tenderness'),
        ('Musculoskeletal',4,'Swelling'),('Musculoskeletal',5,'Muscle Weakness'),('Musculoskeletal',6,'Back Tenderness'),
        ('Skin',1,'Normal'),('Skin',2,'Rash'),('Skin',3,'Pallor'),('Skin',4,'Jaundice'),
        ('Skin',5,'Lesion'),('Skin',6,'Itching'),('Skin',7,'Edema')
    ) as v(category, ord, finding);

    insert into frequency_code (clinic_id, code, meaning_en, sort_order)
    select cid, v.code, v.meaning, v.ord
    from (values
        ('1-0-0','Once daily — morning',1),
        ('0-1-0','Once daily — afternoon',2),
        ('0-0-1','Once daily — night',3),
        ('1-0-1','Twice daily — morning & night',4),
        ('1-1-0','Twice daily — morning & afternoon',5),
        ('0-1-1','Twice daily — afternoon & night',6),
        ('1-1-1','Three times daily',7),
        ('1-1-1-1','Four times daily',8),
        ('1-0-0-1','Morning & night',9),
        ('SOS','As needed',10),
        ('STAT','Immediately / single urgent dose',11),
        ('HS','At bedtime',12),
        ('PRN','As required',13)
    ) as v(code, meaning, ord);

    insert into prescription_option (clinic_id, kind, value, sort_order)
    select cid, 'duration', v, ord
    from unnest(array['3 Days','5 Days','7 Days','10 Days','14 Days','1 Month','Ongoing']) with ordinality as t(v, ord);

    insert into prescription_option (clinic_id, kind, value, sort_order)
    select cid, 'timing', v, ord
    from unnest(array[
        'Before Breakfast','After Breakfast','Before Lunch','After Lunch','Before Dinner','After Dinner',
        'Before Meals','After Meals','With Meals','Empty Stomach','At Bedtime','Any Time of Day'
    ]) with ordinality as t(v, ord);

    insert into service_fee (clinic_id, name, fee, sort_order) values
        (cid, 'Consultation', 1500, 1),
        (cid, 'Follow-up Consultation', 500, 2),
        (cid, 'Procedure', 3000, 3),
        (cid, 'Investigation', 1000, 4),
        (cid, 'Other', 0, 5);
end $$;
