#include "splashscreen.h"

#include <QFontDatabase>
#include <QPainter>
#include <QPainterPath>
#include <QPropertyAnimation>
#include <QApplication>
#include <QRadialGradient>
#include <QScreen>
#include <QTimer>
#include <QTransform>
#include <QtMath>

SplashScreen::SplashScreen(QWidget *parent) : QWidget(parent)
{
    const QSize screenSize = QApplication::primaryScreen()
        ? QApplication::primaryScreen()->geometry().size()
        : QSize(1200, 700);
    setMinimumSize(qMin(640, screenSize.width()), qMin(360, screenSize.height()));
    resize(qMin(1200, screenSize.width()), qMin(700, screenSize.height()));
    setWindowFlags(Qt::FramelessWindowHint | Qt::WindowStaysOnTopHint);
    setAttribute(Qt::WA_TranslucentBackground, false);

    for (int i = 0; i < 180; ++i) {
        qreal x = qreal((i * 37) % 1000) / 1000.0;
        qreal y = qreal((i * 61 + 173) % 1000) / 1000.0;
        particles.append(QPointF(x, y));
    }

    QPropertyAnimation *animation = new QPropertyAnimation(this, "progress", this);
    animation->setDuration(3500);
    animation->setStartValue(0.0);
    animation->setEndValue(1.0);
    animation->setEasingCurve(QEasingCurve::InOutCubic);
    connect(animation, &QPropertyAnimation::finished, this, &SplashScreen::finished);
    animation->start();
}

qreal SplashScreen::progress() const
{
    return m_progress;
}

void SplashScreen::setProgress(qreal value)
{
    m_progress = qBound<qreal>(0.0, value, 1.0);
    update();
}

QFont SplashScreen::calligraphyFont(int pointSize) const
{
    QStringList candidates;
    candidates << QStringLiteral("FZShuTi")
               << QStringLiteral("STXingkai")
               << QStringLiteral("KaiTi")
               << QStringLiteral("Kaiti SC")
               << QStringLiteral("SimSun")
               << QStringLiteral("Microsoft YaHei");

    const QStringList families = QFontDatabase().families();
    for (const QString &candidate : candidates) {
        if (families.contains(candidate)) {
            QFont font(candidate, pointSize, QFont::Black);
            font.setStyleStrategy(QFont::PreferAntialias);
            return font;
        }
    }
    QFont font(QStringLiteral("Microsoft YaHei"), pointSize, QFont::Black);
    font.setStyleStrategy(QFont::PreferAntialias);
    return font;
}

void SplashScreen::paintEvent(QPaintEvent *)
{
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setRenderHint(QPainter::TextAntialiasing, true);

    QRectF r = rect();
    QLinearGradient bg(r.topLeft(), r.bottomRight());
    bg.setColorAt(0.0, QColor("#07110f"));
    bg.setColorAt(0.46, QColor("#10231f"));
    bg.setColorAt(1.0, QColor("#eef4f0"));
    painter.fillRect(r, bg);

    const qreal inkPhase = qBound<qreal>(0.0, m_progress / 0.45, 1.0);
    const qreal revealPhase = qBound<qreal>(0.0, (m_progress - 0.14) / 0.42, 1.0);
    const qreal irisPhase = qBound<qreal>(0.0, (m_progress - 0.44) / 0.28, 1.0);
    const qreal dissolvePhase = qBound<qreal>(0.0, (m_progress - 0.68) / 0.32, 1.0);

    painter.save();
    painter.setPen(Qt::NoPen);
    for (int i = 0; i < particles.size(); ++i) {
        QPointF p = particles[i];
        qreal drift = qSin((m_progress * 4.0 + i) * 1.7) * 0.018;
        QPointF pos(r.left() + (p.x() + drift * (0.5 - p.y())) * r.width(),
                    r.top() + (p.y() + drift) * r.height());
        qreal pull = qMin<qreal>(1.0, inkPhase * 1.18);
        qreal angle = i * 0.6180339887 + m_progress * 5.0;
        qreal spiral = (0.08 + (i % 17) / 17.0 * 0.32) * (1.0 - dissolvePhase * 0.65);
        QPointF target(r.center().x() + qCos(angle) * r.width() * spiral,
                       r.center().y() + qSin(angle * 1.23) * r.height() * spiral * 0.56);
        QPointF mixed = pos * (1.0 - pull) + target * pull;
        if (dissolvePhase > 0.0) {
            QPointF airy(r.center().x() + qCos(angle + i) * r.width() * (0.05 + 0.28 * dissolvePhase),
                         r.center().y() + qSin(angle * 0.7 + i) * r.height() * (0.04 + 0.18 * dissolvePhase));
            mixed = mixed * (1.0 - dissolvePhase) + airy * dissolvePhase;
        }
        int alpha = int((1.0 - dissolvePhase * 0.92) * (38 + (i % 5) * 18));
        QColor dot = (i % 3 == 0) ? QColor(34, 218, 174, alpha) : QColor(242, 248, 245, alpha);
        painter.setBrush(dot);
        qreal size = 1.5 + (i % 7) * 0.55 + inkPhase * 2.2;
        painter.drawEllipse(mixed, size, size);
    }
    painter.restore();

    QPointF center = r.center();
    painter.save();
    painter.setOpacity((1.0 - dissolvePhase * 0.55) * (0.32 + revealPhase * 0.68));
    QRadialGradient titleGlow(center, qMin(r.width(), r.height()) * 0.42);
    titleGlow.setColorAt(0.0, QColor(129, 255, 226, 104));
    titleGlow.setColorAt(0.38, QColor(31, 181, 143, 58));
    titleGlow.setColorAt(1.0, QColor(31, 181, 143, 0));
    painter.setPen(Qt::NoPen);
    painter.setBrush(titleGlow);
    painter.drawEllipse(center, qMin(r.width(), r.height()) * 0.38,
                        qMin(r.width(), r.height()) * 0.24);
    painter.restore();

    painter.save();
    painter.setOpacity((1.0 - dissolvePhase) * (0.28 + irisPhase * 0.72));
    for (int i = 0; i < 5; ++i) {
        qreal radius = (90 + i * 28) * (0.45 + irisPhase * 0.75);
        QRectF ring(center.x() - radius, center.y() - radius, radius * 2, radius * 2);
        QPen pen(QColor(31, 181, 143, 150 - i * 20), 1.3 + i * 0.4);
        pen.setCapStyle(Qt::RoundCap);
        painter.setPen(pen);
        painter.drawArc(ring, int((20 + i * 28 + m_progress * 180) * 16),
                        int((210 - i * 18) * irisPhase * 16));
    }
    painter.restore();

    QFont font = calligraphyFont(qMax(112, int(r.height() * 0.22)));
    QPainterPath textPath;
    textPath.addText(QPointF(0, 0), font, QStringLiteral("智眸"));
    QRectF textBounds = textPath.boundingRect();
    QTransform transform;
    transform.translate(center.x() - textBounds.width() / 2.0,
                        center.y() + textBounds.height() / 2.8);
    QPainterPath placedText = transform.map(textPath);
    QRectF placedBounds = placedText.boundingRect();

    painter.save();
    painter.setOpacity((1.0 - dissolvePhase * 0.5) * 0.34);
    painter.setPen(QPen(QColor("#dcfff6"), 5));
    painter.setBrush(Qt::NoBrush);
    painter.drawPath(placedText);
    painter.restore();

    painter.save();
    painter.setOpacity((1.0 - dissolvePhase * 0.62) * revealPhase * 0.7);
    painter.setPen(QPen(QColor(116, 255, 225, 120), 9, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
    painter.setBrush(Qt::NoBrush);
    painter.drawPath(placedText);
    painter.restore();

    painter.save();
    painter.setClipRect(QRectF(placedBounds.left() - 12,
                              placedBounds.top() - 12,
                              (placedBounds.width() + 24) * revealPhase,
                              placedBounds.height() + 24));
    QLinearGradient ink(placedBounds.topLeft(), placedBounds.bottomRight());
    ink.setColorAt(0.0, QColor("#ffffff"));
    ink.setColorAt(0.34, QColor("#eafff8"));
    ink.setColorAt(0.72, QColor("#74ffe1"));
    ink.setColorAt(1.0, QColor("#1fb58f"));
    painter.setOpacity(1.0 - dissolvePhase * 0.62);
    painter.setPen(QPen(QColor(255, 255, 255, 92), 3.5));
    painter.setBrush(ink);
    painter.drawPath(placedText);
    painter.restore();

    painter.save();
    painter.setClipPath(placedText);
    painter.setOpacity((1.0 - dissolvePhase * 0.7) * revealPhase);
    for (int i = 0; i < 5; ++i) {
        const qreal orbit = qSin((m_progress * 8.0 + i * 1.37));
        const qreal x = placedBounds.left() + placedBounds.width()
            * qBound<qreal>(0.0, revealPhase * 1.18 - 0.18 + i * 0.035 + orbit * 0.025, 1.0);
        const qreal y = placedBounds.center().y()
            + qSin(m_progress * 10.0 + i) * placedBounds.height() * 0.28;
        QRadialGradient spark(QPointF(x, y), placedBounds.width() * (0.08 + i * 0.01));
        spark.setColorAt(0.0, QColor(255, 255, 255, 220));
        spark.setColorAt(0.32, QColor(134, 255, 231, 150));
        spark.setColorAt(1.0, QColor(134, 255, 231, 0));
        painter.setPen(Qt::NoPen);
        painter.setBrush(spark);
        painter.drawEllipse(QPointF(x, y),
                            placedBounds.width() * (0.075 + i * 0.008),
                            placedBounds.height() * 0.34);
    }
    painter.restore();

    painter.save();
    painter.setOpacity((1.0 - dissolvePhase) * revealPhase * (0.7 + 0.3 * qSin(m_progress * 18.0)));
    QLinearGradient sweep(placedBounds.left(), 0, placedBounds.right(), 0);
    sweep.setColorAt(0.0, QColor(255, 255, 255, 0));
    sweep.setColorAt(qBound<qreal>(0.0, revealPhase, 1.0), QColor(140, 255, 229, 180));
    sweep.setColorAt(1.0, QColor(255, 255, 255, 0));
    painter.setPen(QPen(QBrush(sweep), 2));
    painter.drawLine(QPointF(placedBounds.left(), placedBounds.center().y()),
                     QPointF(placedBounds.right(), placedBounds.center().y()));
    painter.restore();

    painter.save();
    painter.setOpacity(dissolvePhase * 0.62);
    QRadialGradient breath(center, qMax(r.width(), r.height()) * 0.56);
    breath.setColorAt(0.0, QColor(237, 244, 240, 190));
    breath.setColorAt(0.42, QColor(31, 181, 143, 70));
    breath.setColorAt(1.0, QColor(237, 244, 240, 0));
    painter.setPen(Qt::NoPen);
    painter.setBrush(breath);
    qreal glowRadius = qMax(r.width(), r.height()) * (0.24 + dissolvePhase * 0.34);
    painter.drawEllipse(center, glowRadius, glowRadius * 0.58);
    painter.restore();
}
