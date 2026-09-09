export const routeScope={'/':'OPERATIONS','/items':'ITEMS','/categories':'CATEGORIES','/users':'USERS','/matches':'EXCHANGES','/disputes':'EXCHANGES','/exchanges':'EXCHANGES','/history-verifications':'HISTORY','/reports':'REPORTS','/planned':'OPERATIONS'};
export const canVisit=(user,path)=>!routeScope[path]||user?.permissions?.includes('ALL')||user?.permissions?.includes(routeScope[path]);
export const firstAllowed=user=>Object.keys(routeScope).find(path=>canVisit(user,path))||'/login';
