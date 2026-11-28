#!/usr/bin/env python3
"""ES-First 2차 검증 — 새 평가 문항(2026-11-28). 문서 기준: code-and-pick/pmart-all-docs 1725acb.

규칙(사용자·검토 합의):
- 같은 원본에서 바꿔 쓴 질문·같은 조항을 묻는 변형은 같은 group 으로 묶고, 질문군 단위로 dev/final 로 나눈다.
- label: GROUNDED(근거 있음 — 답이 '아니요'여도 문서에 적혀 있으면 여기) / NO_GROUNDING(그 역할이 볼 수 있는 문서 범위에 답이 없음) /
  NEEDS_CLARIFICATION(추가 질문 필요) / REALTIME(실시간 데이터 필요).
- NO_GROUNDING 은 문서 전체에서 낱말을 찾아 확인한 기록(checked)을 붙인다. '검색이 안 됨'과 '문서에 답이 없음'을 섞지 않는다.
- 판단이 애매하면 uncertain=True 와 이유 — 성능 집계(확정 정답)에서 뺀다.
- evidence.excerpt 는 원문 그대로여야 한다 — 이 스크립트가 기준 문서와 대조해 다르면 실패.

  python build_questions.py <pmart-all-docs 경로> <출력 questions.json>
"""
import hashlib
import json
import os
import random
import re
import sys

COMMIT = "1725acb02d9d46cc3ac4030ca2b56843fd232ec3"


def ev(doc, section, excerpt):
    return {"doc": doc, "section": section, "excerpt": excerpt}


BO1 = "operator/backoffice/01-staff-account-login.md"
BO4 = "operator/backoffice/04-personal-info-access.md"
CM5 = "operator/commerce/05-reviews.md"
CM7 = "operator/commerce/07-cart.md"
CM2 = "operator/commerce/02-pricing-promotions.md"
DL2 = "operator/delivery/02-arrival-slots-promise.md"
OC1 = "operator/order_claim/01-order-amount.md"
OC8 = "operator/order_claim/08-delivery-issue-consult.md"
WL = "customer/customer_app/03-wishlist.md"
CP = "customer/customer_app/04-consents-privacy.md"
NT = "customer/customer_app/15-notifications.md"
RV = "customer/customer_app/16-reviews.md"
FA = "customer/faq/12-account.md"

# (group, role, label, answer, evidence[], reason, [(type, q)], uncertain)
GROUPS = [
    # ---------------- 운영자 ----------------
    ("OG01", "operator", "GROUNDED", "최대 12시간, 지나면 다시 로그인",
     [ev(BO1, "제6조 (로그인 유지)", "한 번 로그인하면 최대 12시간까지 이어서 쓸 수 있으며, 12시간이 지나면 다시 로그인해야 합니다.")],
     "제6조 1항에 그대로 적혀 있음",
     [("ORIGINAL", "백오피스는 한 번 로그인하면 몇 시간까지 쓸 수 있어요?"),
      ("PARAPHRASE", "백오피스 로그인 유지 시간이 얼마나 돼요?"),
      ("PARAPHRASE", "아침에 로그인했는데 저녁에 다시 로그인하라고 떠요. 왜 그래요?")], None),
    ("OG02", "operator", "GROUNDED", "업무 단말은 30분 동안 쓰지 않으면 다시 로그인",
     [ev(BO1, "제6조 (로그인 유지)", "업무 단말은 로그인할 때마다 비밀번호를 넣어야 하며, 30분 동안 쓰지 않으면 다시 로그인해야 합니다.")],
     "제6조 3항",
     [("ORIGINAL", "PDA는 얼마 동안 안 쓰면 다시 로그인해야 하나요?"),
      ("PARAPHRASE", "현장 작업 단말기 자동 로그아웃 시간이 몇 분이에요?")], None),
    ("OG03", "operator", "GROUNDED", "비밀번호 설정 안내 메일 링크는 3일",
     [ev(BO1, "제3조 (계정 발급)", "안내 메일의 링크는 3일 동안 쓸 수 있습니다.")],
     "제3조 5항",
     [("ORIGINAL", "신규 직원 비밀번호 설정 메일 링크는 며칠 동안 유효해요?"),
      ("PARAPHRASE", "입사자가 비밀번호 설정 링크를 늦게 눌렀는데 언제까지 쓸 수 있죠?")], None),
    ("OG04", "operator", "GROUNDED", "아니요 — 이름으로는 찾을 수 없음(고객번호·휴대폰 번호 완전 일치만)",
     [ev(BO4, "제3조 (찾기)", "이름으로는 고객이나 라이더를 찾을 수 없습니다.")],
     "답이 '아니요'인 질문 — 문서에 명시돼 근거 있음",
     [("ORIGINAL", "백오피스에서 고객 이름으로 검색할 수 있나요?"),
      ("PARAPHRASE", "고객 이름만 알면 고객 정보를 조회할 수 있어요?")], None),
    ("OG05", "operator", "GROUNDED", "열람 기록은 2년 보관 뒤 파기",
     [ev(BO4, "제5조 (열람 기록)", "열람 기록은 2년 동안 보관한 뒤 파기합니다.")],
     "제5조 3항",
     [("ORIGINAL", "개인정보 원문 열람 기록은 얼마나 보관해요?"),
      ("PARAPHRASE", "직원이 원문을 열람한 기록은 몇 년 지나면 파기돼요?")], None),
    ("OG06", "operator", "GROUNDED", "아니요 — 본인 열람 기록을 보는 기능 없음, 운영 관리자만 조회",
     [ev(BO4, "제5조 (열람 기록)", "열람 기록은 운영 관리자만 조회할 수 있습니다. 본인의 열람 기록을 따로 보는 기능은 없습니다.")],
     "답이 '아니요'인 질문",
     [("ORIGINAL", "직원이 자기 개인정보 열람 기록을 직접 볼 수 있나요?")], None),
    ("OG07", "operator", "GROUNDED", "사유 2자 이상 200자 이하",
     [ev(BO4, "제4조 (원문 열람)", "사유는 2자 이상 200자 이하로 적어야 합니다.")],
     "제4조 1항",
     [("ORIGINAL", "개인정보 원문 열람 사유는 몇 글자로 써야 해요?")], None),
    ("OG08", "operator", "GROUNDED", "아니요 — 직원이 리뷰를 숨기거나 지우는 기능 없음, 고객이 직접 지우도록 안내",
     [ev(CM5, "제8조 (직원의 리뷰 처리)", "현재 직원이 리뷰를 숨기거나 지우는 기능과 고객의 리뷰 신고 기능은 없습니다."),
      ev(CM5, "제8조 (직원의 리뷰 처리)", "고객이 리뷰 삭제를 원하면 고객이 직접 앱에서 지우도록 안내합니다.")],
     "제8조 1·3항",
     [("ORIGINAL", "악성 리뷰를 직원이 숨길 수 있나요?"),
      ("PARAPHRASE", "고객이 자기가 쓴 별점 1점 리뷰를 지워 달라고 하면 어떻게 해요?")], None),
    ("OG09", "operator", "GROUNDED", "마지막으로 바꾼 때부터 30일 지나면 만료",
     [ev(CM7, "제3조 (보존 기간)", "회원과 비회원의 장바구니는 마지막으로 바꾼 때부터 30일이 지나면 만료됩니다.")],
     "제3조 1항",
     [("ORIGINAL", "장바구니에 담은 상품은 며칠 동안 남아 있어요?"),
      ("PARAPHRASE", "장바구니 보존 기간이 어떻게 돼요?"),
      ("PARAPHRASE", "고객이 한 달 전에 담아 둔 장바구니가 없어졌다고 해요")], None),
    ("OG10", "operator", "GROUNDED", "아니요 — 담기는 재고 예약이 아님, 재고 확보는 주문 때",
     [ev(CM7, "제4조 (담기와 수량 변경)", "장바구니에 담는 것은 재고를 예약하는 것이 아닙니다.")],
     "제4조 3항",
     [("ORIGINAL", "장바구니에 담으면 재고가 잡히나요?")], None),
    ("OG11", "operator", "GROUNDED", "결제 전 잡아 둔 자리는 10분 유지, 그 안에 확정 안 되면 풀림",
     [ev(DL2, "제4조 (정원과 점유)", "잡아 둔 자리는 10분 동안 유지되며, 그 안에 주문이 확정되지 않으면 풀립니다.")],
     "제4조 1항",
     [("ORIGINAL", "예약배달 시간대를 고르면 자리는 몇 분 동안 잡아 둬요?"),
      ("PARAPHRASE", "결제 전에 고른 도착 시간대 자리는 언제 풀려요?")], None),
    ("OG12", "operator", "GROUNDED", "한집배달·알뜰배달은 주문 확정 뒤 50분",
     [ev(DL2, "제6조 (배송 약속 시각)", "한집배달과 알뜰배달의 배송 약속 시각은 주문이 확정된 때부터 50분 뒤입니다.")],
     "제6조 1항",
     [("ORIGINAL", "한집배달의 배송 약속 시각은 주문 후 몇 분이에요?"),
      ("PARAPHRASE", "알뜰배달은 언제까지 도착해야 약속을 지킨 거예요?")], None),
    ("OG13", "operator", "GROUNDED", "예상 전달 시각이 약속보다 20분 넘게 늦을 것으로 보이면 한 번",
     [ev(DL2, "제7조 (배차 지연 안내)", "배차를 기다리는 동안 예상 전달 시각이 배송 약속 시각보다 20분 넘게 늦어질 것으로 보이면, 회사는 그 배송을 배차 지연으로 한 번 알립니다.")],
     "제7조 1항",
     [("ORIGINAL", "배차 지연 알림은 몇 분 늦어질 것 같을 때 나가요?")], None),
    ("OG14", "operator", "GROUNDED", "아니요 — 08시 이전에 시작하는 도착 시간대는 만들지 않음",
     [ev(DL2, "제3조 (도착 시간대)", "08시 이전에 시작하는 도착 시간대는 만들지 않습니다.")],
     "답이 '아니요'인 질문",
     [("ORIGINAL", "예약배달로 아침 7시 도착 시간대를 고를 수 있어요?")], None),
    ("OG15", "operator", "GROUNDED", "아니요 — 포인트·적립금·멤버십 혜택은 운영하지 않음",
     [ev(OC1, "제8조 (결제 금액)", "포인트, 선물함, 멤버십 혜택은 없습니다."),
      ev(CM2, "제2조", "포인트, 적립금, 멤버십 혜택")],
     "운영자 문서 범위에는 '없음'이 적혀 있어 근거 있음(고객 범위의 같은 질문 CG13 은 근거 없음)",
     [("ORIGINAL", "리뷰 쓰면 고객한테 포인트 적립해 주나요?"),
      ("PARAPHRASE", "적립금 제도가 있어요?")], None),
    ("OG16", "operator", "GROUNDED", "아니요 — 지연에 대한 쿠폰·적립금 보상 없음(보상금은 환불 정책 제7조)",
     [ev(OC8, "제3조 (배송 지연)", "지연에 대한 쿠폰·적립금 보상은 없으며, 보상금은 PMart 환불 정책 제7조를 따릅니다.")],
     "답이 '아니요'인 질문",
     [("ORIGINAL", "배송이 늦어지면 고객에게 보상 쿠폰을 주나요?")], None),
    # 근거 없음(어휘는 비슷 — Hard Negative)
    ("ON01", "operator", "NO_GROUNDING", None, [],
     "문서 88개에 비밀번호 변경 주기(개월·90일·주기) 없음 — 비밀번호 설정·잠금만 있음",
     [("HARD_NEGATIVE", "백오피스 비밀번호는 몇 달마다 바꿔야 하나요?")], None),
    ("ON02", "operator", "NO_GROUNDING", None, [],
     "문서 88개에 '수당' 없음 — 근무·정산 정책은 라이더 수행료만",
     [("HARD_NEGATIVE", "센터 야간 근무 수당은 얼마예요?")], None),
    ("ON03", "operator", "NO_GROUNDING", None, [],
     "문서 88개에 리뷰 동영상·'영상' 없음 — 사진 형식(JPEG·PNG·WEBP)만 적혀 있음",
     [("HARD_NEGATIVE", "리뷰에 동영상도 올릴 수 있게 되어 있나요?")],
     "사진 형식만 나열돼 있어 '동영상 불가'로 읽을 수도 있음 — 형식 목록이 사진에만 해당하는지 문서가 말하지 않는다"),
    ("ON04", "operator", "NO_GROUNDING", None, [],
     "장바구니 정책에 담을 수 있는 상품 종류 수 상한 없음(기초상품별 구성 낱개 10개 상한만)",
     [("HARD_NEGATIVE", "장바구니에 상품을 최대 몇 종류까지 담을 수 있어요?")],
     "'상한이 없다'로 답할지 '문서에 없음'인지 문서가 정하지 않음 — 수량 상한(제4조)과 헷갈릴 수 있다"),
    ("ON05", "operator", "NO_GROUNDING", None, [],
     "도착 시간대 정원은 '센터마다 정한 값'(제2조·제4조 4항)만 있고 숫자는 문서에 없음",
     [("HARD_NEGATIVE", "예약배달 시간대 정원은 한 시간에 몇 건이에요?")],
     "숫자는 없지만 '센터마다 정해진 값'이라는 답은 할 수 있음 — 근거 없음/근거 있음 경계"),
    # 추가 질문·실시간
    ("OC01", "operator", "NEEDS_CLARIFICATION", None,
     [ev(BO1, "제6조 (로그인 유지)", "한 번 로그인하면 최대 12시간까지 이어서 쓸 수 있으며, 12시간이 지나면 다시 로그인해야 합니다."),
      ev(BO1, "제6조 (로그인 유지)", "30분 동안 쓰지 않으면 다시 로그인해야 합니다.")],
     "백오피스(12시간)·업무 단말(30분)이 달라 어느 화면인지 물어야 함",
     [("CLARIFY", "로그인은 얼마 동안 유지돼요?")],
     "제6조 한 조항에 두 경우가 다 있어 '두 경우를 함께 답'할 수도 있음 — 추가 질문이 필요한지 문서가 정하지 않음(2026-11-28 dev 결과 보고 미확정으로)"),
    ("OR01", "operator", "REALTIME", None, [], "지금 목록의 건수 — 업무 조회 필요",
     [("REALTIME", "지금 배차 지연 목록에 몇 건 올라와 있어요?")], None),
    # ---------------- 고객 ----------------
    ("CG01", "customer", "GROUNDED", "회원 한 명당 200개",
     [ev(WL, "제4조 (찜 개수)", "회원 한 명이 찜할 수 있는 상품은 200개까지입니다.")],
     "제4조 1항(FAQ 12 에도 같은 내용)",
     [("ORIGINAL", "찜은 몇 개까지 할 수 있어요?"),
      ("PARAPHRASE", "하트 누른 상품이 너무 많은데 한도가 있나요?")], None),
    ("CG02", "customer", "GROUNDED", "아니요 — 재입고 알림 제공하지 않음",
     [ev(WL, "제5조 (품절·판매 종료 상품)", "회사는 찜한 상품의 재입고 알림을 제공하지 않습니다.")],
     "답이 '아니요'인 질문",
     [("ORIGINAL", "찜한 상품이 다시 들어오면 알림이 오나요?"),
      ("PARAPHRASE", "품절된 찜 상품이 재입고되면 알려 주세요")], None),
    ("CG03", "customer", "GROUNDED", "처음 받은 때부터 14일 안",
     [ev(RV, "제3조 (작성 기한)", "리뷰는 상품을 처음 받은 때부터 14일 안에 작성할 수 있습니다.")],
     "제3조 1항",
     [("ORIGINAL", "리뷰는 받은 뒤 며칠 안에 써야 해요?"),
      ("PARAPHRASE", "상품 받고 2주가 지나도 후기를 쓸 수 있나요?")], None),
    ("CG04", "customer", "GROUNDED", "처음 등록한 때부터 3일 안에만 수정",
     [ev(RV, "제4조 (수정과 삭제)", "리뷰는 처음 등록한 때부터 3일 안에만 수정할 수 있습니다.")],
     "제4조 1항",
     [("ORIGINAL", "리뷰 수정은 언제까지 할 수 있어요?"),
      ("PARAPHRASE", "후기 올리고 일주일이 지났는데 고칠 수 있어요?")], None),
    ("CG05", "customer", "GROUNDED", "아니요 — 삭제하면 같은 상품에 다시 쓸 수 없음",
     [ev(RV, "제4조 (수정과 삭제)", "리뷰를 삭제하면 같은 상품에 리뷰를 다시 작성할 수 없습니다.")],
     "답이 '아니요'인 질문",
     [("ORIGINAL", "리뷰를 지우고 다시 쓸 수 있나요?")], None),
    ("CG06", "customer", "GROUNDED", "5장까지, 1장 10MB 이하 JPEG·PNG·WEBP",
     [ev(RV, "제5조 (사진)", "리뷰 1건에 사진을 5장까지 올릴 수 있습니다.")],
     "제5조 1·2항",
     [("ORIGINAL", "리뷰에 사진 몇 장까지 올릴 수 있어요?"),
      ("PARAPHRASE", "후기 사진 용량 제한이 있나요?")], None),
    ("CG07", "customer", "GROUNDED", "예 — 주문·배송·환불 등 거래 알림은 수신 동의와 관계없이 보냄",
     [ev(CP, "제3조 (마케팅 정보 수신)", "주문·배송·환불 등 거래에 필요한 알림은 마케팅 정보 수신 동의와 관계없이 보냅니다.")],
     "제3조 4항",
     [("ORIGINAL", "마케팅 알림을 꺼도 주문 알림은 와요?"),
      ("PARAPHRASE", "광고 수신을 거부하면 배송 알림도 안 오나요?")], None),
    ("CG08", "customer", "GROUNDED", "아니요 — 거래에 필요한 알림이라 끌 수 없음",
     [ev(NT, "제2조 (앱 알림함)", "이 알림은 거래에 필요한 안내이므로 회원이 끌 수 없습니다.")],
     "답이 '아니요'인 질문",
     [("ORIGINAL", "배송 완료 알림을 끌 수 있나요?")], None),
    ("CG09", "customer", "GROUNDED", "아니요 — 주소를 찾는 데에만 쓰고 위치는 저장하지 않음",
     [ev(CP, "제4조 (위치 정보)", "휴대폰의 위치는 주소를 찾는 데에만 쓰며, 위치 자체는 저장하지 않습니다.")],
     "제4조 2항",
     [("ORIGINAL", "현재 위치로 주소를 찾으면 제 위치가 저장되나요?")], None),
    ("CG10", "customer", "GROUNDED", "같은 번호로는 탈퇴 후 30일이 지나야 재가입",
     [ev(FA, "탈퇴한 뒤 다시 가입할 수 있나요?", "같은 휴대폰 번호로는 **탈퇴 후 30일이 지나야** 다시 가입할 수 있어요."),
      ev("customer/customer_app/02-withdrawal.md", "제6조 (재가입)", "탈퇴한 회원은 같은 휴대폰 번호로 탈퇴 후 30일이 지나야 다시 가입할 수 있습니다."),
      ev("customer/customer_app/02-withdrawal.md", "제5조 (탈퇴 후 정보 처리)", "확인된 휴대폰 번호: 탈퇴 후 30일(같은 번호의 재가입 제한)")],
     "FAQ 12(확정 정책)·고객 앱 탈퇴 안내 제6조·제5조 — 2026-11-28 dev 결과를 보고 근거를 더함(처음 표시가 좁았음, dev 문항)",
     [("ORIGINAL", "탈퇴하고 같은 번호로 바로 다시 가입할 수 있나요?"),
      ("PARAPHRASE", "탈퇴 후 재가입은 언제부터 돼요?")], None),
    ("CG11", "customer", "GROUNDED", "30일 동안 앱을 쓰지 않으면 다시 로그인",
     [ev(FA, "로그인은 어떻게 하나요?", "30일 동안 앱을 쓰지 않으면 다시 로그인해야 해요.")],
     "FAQ 12(확정 정책)",
     [("ORIGINAL", "앱을 오래 안 쓰면 로그아웃되나요?")], None),
    ("CG12", "customer", "GROUNDED", "로그인한 기기로만 보내고 로그아웃한 기기로는 보내지 않음",
     [ev(NT, "제4조 (휴대폰 알림)", "휴대폰 알림은 회원이 로그인한 기기로 보내며, 로그아웃한 기기로는 보내지 않습니다.")],
     "제4조 3항",
     [("ORIGINAL", "로그아웃한 폰에도 배송 알림이 오나요?")], None),
    # 근거 없음
    ("CG13", "customer", "NO_GROUNDING", None, [],
     "고객 문서 범위(customer_app·faq)에 '포인트·적립' 없음 — 운영자 문서에만 '없음'이 있어 고객 RAG 근거로 쓸 수 없음(OG15 와 대비)",
     [("HARD_NEGATIVE", "리뷰 쓰면 적립금 주나요?")], None),
    ("CN01", "customer", "NO_GROUNDING", None, [],
     "문서 88개에 가격 인하 알림 없음(재입고 알림 없음만 있음)",
     [("HARD_NEGATIVE", "찜한 상품 가격이 내려가면 알려 주나요?")],
     "재입고 알림이 없다는 조항으로 '알림 없음'이라 답할 수도 있음 — 가격 알림은 문서가 말하지 않는다"),
    ("CN02", "customer", "NO_GROUNDING", None, [],
     "문서 88개에 리뷰 동영상 없음(사진 형식만)",
     [("HARD_NEGATIVE", "리뷰에 동영상도 올릴 수 있어요?")],
     "사진 형식만 나열 — '동영상 불가'로 읽을 수도 있음"),
    ("CN03", "customer", "NO_GROUNDING", None, [],
     "문서 88개에 알림 소리 설정 없음",
     [("HARD_NEGATIVE", "배송 알림 소리를 바꿀 수 있나요?")], None),
    ("CN04", "customer", "NO_GROUNDING", None, [],
     "문서 88개에 생일 쿠폰 없음(가입 축하·보상 쿠폰만)",
     [("HARD_NEGATIVE", "생일 쿠폰도 주나요?")], None),
    ("CC01", "customer", "NEEDS_CLARIFICATION", None, [],
     "무엇의 기한인지(리뷰 작성·수정, 재가입, 장바구니 등) 알 수 없음",
     [("CLARIFY", "기한이 며칠이에요?")], None),
    ("CR01", "customer", "REALTIME", None, [], "회원 본인의 찜 목록 상태 — 계정 데이터 필요",
     [("REALTIME", "제가 찜한 상품 중에 지금 품절된 게 뭐예요?")], None),
]

NO_GROUNDING_CHECKS = {
    "ON01": "비밀번호.*(주기|개월|90일|변경 주기)",
    "ON02": "수당",
    "ON03": "동영상|영상",
    "CN02": "동영상|영상",
    "CN01": "가격.*(내려|인하|하락).*알림|가격 알림",
    "CN03": "알림 소리|소리",
    "CN04": "생일",
}


def norm(s):
    return re.sub(r"\s+", " ", s).strip()


def main():
    docs, out = sys.argv[1], sys.argv[2]
    corpus = {}
    for root, _, files in os.walk(docs):
        if ".git" in root:
            continue
        for f in files:
            if f.endswith(".md") and f != "README.md":
                p = os.path.relpath(os.path.join(root, f), docs).replace("\\", "/")
                corpus[p] = open(os.path.join(root, f), encoding="utf-8").read()
    assert len(corpus) == 88, len(corpus)
    questions = []
    for g, role, label, answer, evs, reason, qs, uncertain in GROUPS:
        for e in evs:
            body = corpus.get(e["doc"])
            assert body is not None, ("문서 없음", g, e["doc"])
            assert norm(e["excerpt"]) in norm(body), ("원문과 다름", g, e["excerpt"][:30])
            assert e["section"] in body, ("조항 없음", g, e["section"])
        checked = None
        if g in NO_GROUNDING_CHECKS:
            pat = re.compile(NO_GROUNDING_CHECKS[g])
            hits = [p for p, b in corpus.items() if pat.search(b) and (role == "operator" or p.startswith("customer/"))]
            checked = {"pattern": NO_GROUNDING_CHECKS[g], "scope": role, "docsWithMatch": hits}
            assert not hits, ("근거 없음인데 문서에 있음", g, hits)
        for i, (t, q) in enumerate(qs):
            questions.append({
                "id": f"{g}-{i + 1}", "group": g, "role": role, "type": t, "q": q, "label": label,
                "answer": answer, "evidence": evs, "reason": reason, "noGroundingCheck": checked,
                "uncertain": uncertain is not None, "uncertainReason": uncertain, "commit": COMMIT})
    # 질문군 단위 dev/final 분할: 역할 × 라벨마다 군을 섞어 약 6:4(고정 seed — 결과 파일로 고정)
    rng = random.Random(20261128)
    split = {}
    strata = {}
    for g, role, label, *_ in GROUPS:
        strata.setdefault((role, label), []).append(g)
    for key, gs in sorted(strata.items()):
        gs = sorted(gs)
        rng.shuffle(gs)
        n_dev = max(1, round(len(gs) * 0.6)) if len(gs) > 1 else 1
        for i, g in enumerate(gs):
            split[g] = "dev" if i < n_dev else "final"
    for q in questions:
        q["split"] = split[q["group"]]
    data = {"_comment": "ES-First 2차 검증 새 문항(2026-11-28). 문서 기준 pmart-all-docs " + COMMIT
            + ". 질문군(group) 단위로 dev/final 분할 — final 은 규칙·가중치를 고정한 뒤 한 번만 쓴다. build_questions.py 로 만든다.",
            "questions": questions}
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    import collections
    c = collections.Counter((q["split"], q["role"], q["label"], q["uncertain"]) for q in questions)
    for k in sorted(c):
        print(k, c[k])
    print("questions", len(questions), "groups", len(GROUPS),
          "sha256", hashlib.sha256(open(out, "rb").read()).hexdigest()[:12])


if __name__ == "__main__":
    main()
