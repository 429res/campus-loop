-- Entirely fictional fixtures. Re-running inserts missing rows; never resets or deletes existing data.
-- NULL hashes make these three visible owners impossible to log in as.
INSERT INTO cl_user(id,username,password_hash,display_name,role,status)
 SELECT 1001,'demo_leaf',NULL,'叶同学 · 虚构演示','USER','ACTIVE' WHERE NOT EXISTS(SELECT 1 FROM cl_user WHERE id=1001 OR username='demo_leaf');
INSERT INTO cl_user(id,username,password_hash,display_name,role,status)
 SELECT 1002,'demo_sky',NULL,'蓝同学 · 虚构演示','USER','ACTIVE' WHERE NOT EXISTS(SELECT 1 FROM cl_user WHERE id=1002 OR username='demo_sky');
INSERT INTO cl_user(id,username,password_hash,display_name,role,status)
 SELECT 1003,'demo_moon',NULL,'月同学 · 虚构演示','USER','ACTIVE' WHERE NOT EXISTS(SELECT 1 FROM cl_user WHERE id=1003 OR username='demo_moon');
INSERT INTO cl_item(id,owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,review_basis)
 SELECT 2001,1001,'线性代数与手写笔记','虚构演示物品。内页干净，附课堂知识整理；希望交换宿舍用的小型数码设备。',1,4,'["教材","学习"]',2,'["便携"]','AVAILABLE','LEGACY_DIRECT' WHERE NOT EXISTS(SELECT 1 FROM cl_item WHERE id=2001);
INSERT INTO cl_item(id,owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,review_basis)
 SELECT 2002,1002,'便携蓝牙音箱','虚构演示物品。功能正常，适合书桌和宿舍，希望换一本下学期教材。',2,4,'["便携","音乐"]',1,'["教材"]','AVAILABLE','LEGACY_DIRECT' WHERE NOT EXISTS(SELECT 1 FROM cl_item WHERE id=2002);
INSERT INTO cl_item(id,owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,review_basis)
 SELECT 2003,1002,'轻巧有线耳机','虚构演示物品。包装完整，适合自习，希望换校园运动用品。',2,5,'["便携","自习"]',3,'["运动"]','AVAILABLE','LEGACY_DIRECT' WHERE NOT EXISTS(SELECT 1 FROM cl_item WHERE id=2003);
INSERT INTO cl_item(id,owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,review_basis)
 SELECT 2004,1003,'羽毛球拍与拍套','虚构演示物品。球拍无裂纹，有正常使用痕迹，希望换学习类书籍。',3,3,'["运动","户外"]',1,'["学习"]','AVAILABLE','LEGACY_DIRECT' WHERE NOT EXISTS(SELECT 1 FROM cl_item WHERE id=2004);
INSERT INTO cl_item(id,owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,review_basis)
 SELECT 2005,1001,'手作编织工具包','虚构演示物品。含基础工具与少量线材，希望换宿舍绿植。',5,4,'["手作"]',6,'["耐养"]','AVAILABLE','LEGACY_DIRECT' WHERE NOT EXISTS(SELECT 1 FROM cl_item WHERE id=2005);
INSERT INTO cl_item(id,owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,review_basis)
 SELECT 2006,1003,'桌面收纳与阅读灯','虚构演示物品。适合宿舍书桌，想寻找户外运动装备。',4,4,'["宿舍","收纳"]',3,'["户外"]','AVAILABLE','LEGACY_DIRECT' WHERE NOT EXISTS(SELECT 1 FROM cl_item WHERE id=2006);
