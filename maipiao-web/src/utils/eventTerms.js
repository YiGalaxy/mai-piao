/**
 * 不同品类的活动该用哪套说法。
 *
 * 对后端来说，电影票和演唱会票是同一笔交易 —— 一个座位、一个价格、一笔订单
 * —— 这是有意为之。但对拿着票的人来说，两者并不是一回事。而界面最初是照着
 * 电影写的，后来直接套用到所有品类上：它告诉去看演唱会的人他的「影片」八点
 * 开始，把他送到「影院」，还让他去影院票房取票。
 *
 * 所以用词跟着品类走。只在一个地方决定，因为另一种做法是把同一个三元表达式
 * 抄遍每个视图，最后互相打架。
 */

const MOVIE = 'MOVIE'

/** 卖的是什么：一部影片，还是一场演出。 */
const SUBJECT = {
  MOVIE: '影片',
  CONCERT: '演出',
  TALK_SHOW: '演出',
  THEATER: '演出',
  MUSICAL: '演出'
}

/** 在哪儿办。演唱会不在影院。 */
const VENUE = {
  MOVIE: '影院',
  CONCERT: '场馆',
  TALK_SHOW: '场馆',
  THEATER: '剧场',
  MUSICAL: '剧场'
}

/** 场馆里的那个厅/场地。 */
const PLACE = {
  MOVIE: '影厅',
  CONCERT: '场地',
  TALK_SHOW: '场地',
  THEATER: '剧场',
  MUSICAL: '剧场'
}

/**
 * 场次是怎么来的。
 *
 * 影院是排片：同一部片子，一天好几场，连着放几周。演唱会是官宣：某一天、
 * 某个场馆，提前几个月定下。「排片」是排期的说法，放在演唱会列表上
 * 读起来就是胡话。
 */
const SCHEDULE = {
  MOVIE: '排片',
  CONCERT: '场次',
  TALK_SHOW: '场次',
  THEATER: '场次',
  MUSICAL: '场次'
}

/** 票怎么取。 */
const COLLECT = {
  MOVIE: '请凭取票码到影院自助机取票',
  CONCERT: '请凭电子票及本人证件入场',
  TALK_SHOW: '请凭电子票入场',
  THEATER: '请凭电子票入场',
  MUSICAL: '请凭电子票入场'
}

function pick(table, category) {
  return table[category] || table.movie || table[MOVIE] || ''
}

/** 是电影时为真 —— 电影是唯一有「影院」的品类。 */
export function isFilm(category) {
  return !category || category === MOVIE
}

export function subjectOf(category) {
  return pick(SUBJECT, category)
}

export function venueOf(category) {
  return pick(VENUE, category)
}

export function placeOf(category) {
  return pick(PLACE, category)
}

export function scheduleOf(category) {
  return pick(SCHEDULE, category)
}

export function collectHintOf(category) {
  return pick(COLLECT, category)
}
